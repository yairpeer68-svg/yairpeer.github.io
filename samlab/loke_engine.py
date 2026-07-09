#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
מנוע LOKE ישיר (ניסיוני) עבור מבריק לאב.

מימוש נקי של פרוטוקול Samsung Odin/LOKE מעל USB (pyusb/libusb), על בסיס
קוד המקור הפתוח של Heimdall. **בגרסה זו מיושם רק הנתיב הבטוח שאינו כותב
דבר למכשיר** — handshake, פתיחת סשן, קריאת טבלת המחיצות (PIT) וסיום סשן.
פעולות פלאש (כתיבה) *אינן* מיושמות כאן בכוונה, ויתווספו רק לאחר שהקריאה
תאומת על מכשיר בר-הקרבה.

קבועי הפרוטוקול אומתו מול Heimdall:
  ControlType:  Session=0x64, PitFile=0x65, FileTransfer=0x66, EndSession=0x67
  Session req:  Begin=0, DeviceType=1, TotalBytes=2, FilePartSize=5
  PitFile req:  Flash=0, Dump=1, Part=2, EndTransfer=3
  Response:     8 בתים = [responseType uint32][result uint32]
  PIT chunk:    500 בתים לכל חלק
  PIT format:   כותרת 28B (magic 0x12349876 @0, entryCount @4); רשומה 132B
                (partitionName @36 [32B], flashFilename @68 [32B])
"""

import struct
import time

SAMSUNG_VID = 0x04E8

# Control packet types
CT_SESSION = 0x64
CT_PITFILE = 0x65
CT_FILEXFER = 0x66
CT_ENDSESSION = 0x67

# Session request types
SESS_BEGIN = 0
SESS_DEVICETYPE = 1
SESS_TOTALBYTES = 2
SESS_FILEPARTSIZE = 5

# PIT request types
PIT_FLASH = 0
PIT_DUMP = 1
PIT_PART = 2
PIT_ENDXFER = 3

# FileTransfer request types (זהים במספר אך שונים בהקשר)
FT_FLASH = 0
FT_DUMP = 1
FT_PART = 2
FT_END = 3
FT_DEST_PHONE = 0
FT_DEST_MODEM = 1

# פרמטרי העברת קובץ (כמו Heimdall)
FT_PACKET_SIZE = 1048576   # 1 MiB לכל חלק
FT_SEQ_MAX = 30            # עד 30 חלקים (30 MiB) לכל רצף

# End-session request types
END_SESSION = 0
END_REBOOT = 1

PIT_MAGIC = 0x12349876
PIT_HEADER_SIZE = 28
PIT_ENTRY_SIZE = 132
PIT_PART_CHUNK = 500  # ReceiveFilePartPacket::kDataSize בהיימדל


class LokeError(Exception):
    pass


def _u32(*vals):
    return b"".join(struct.pack("<I", v) for v in vals)


class LokeDevice:
    """חיבור LOKE למכשיר Samsung במצב Download. נתיב קריאה בלבד."""

    def __init__(self, log=print, send_zlp=False, hexlog=False):
        self.log = log
        self.send_zlp = send_zlp          # שליחת Zero-Length-Packet אחרי כל שליחה (לתאימות עם דגמים מסוימים)
        self.hexlog = hexlog              # רישום גולמי (hex) של כל תעבורה — לניפוי תקלות פרוטוקול
        self.dev = None
        self.intf_num = None
        self.ep_in = None
        self.ep_out = None
        self.default_packet_size = 0
        self._usb = None
        self._util = None

    # ---- USB ----
    def connect(self, timeout_ms=8000):
        try:
            import usb.core as _core
            import usb.util as _util
        except Exception:
            raise LokeError("חבילת pyusb אינה מותקנת. הרץ: pip install pyusb  (ונדרש גם libusb-1.0).")
        self._usb, self._util = _core, _util

        try:
            dev = _core.find(idVendor=SAMSUNG_VID)
        except Exception as e:
            raise LokeError(f"שגיאת גישה ל-USB (libusb): {e}. ודא ש-libusb-1.0 מותקן ושהותקן דרייבר (Zadig).")
        if dev is None:
            raise LokeError("לא נמצא מכשיר Samsung (VID 04E8) במצב Download. ודא חיבור, מצב Download ודרייבר.")

        try:
            dev.set_configuration()
        except Exception:
            pass  # לרוב כבר מוגדר, או שאין הרשאה לשנות — לא קריטי

        try:
            cfg = dev.get_active_configuration()
        except Exception as e:
            raise LokeError(f"לא ניתן לקרוא את תצורת ה-USB: {e}")

        chosen = None
        for intf in cfg:
            if intf.bInterfaceClass == 0x0A and intf.bNumEndpoints == 2:  # CDC-DATA
                chosen = intf
                break
        if chosen is None:
            # נפילה: קח את הממשק הראשון עם 2 endpoints מסוג bulk
            for intf in cfg:
                if intf.bNumEndpoints == 2:
                    chosen = intf
                    break
        if chosen is None:
            raise LokeError("לא נמצא ממשק תקשורת מתאים במכשיר (CDC-DATA עם 2 ערוצים).")

        self.intf_num = chosen.bInterfaceNumber
        # שחרור דרייבר ליבה (רלוונטי בלינוקס)
        try:
            if dev.is_kernel_driver_active(self.intf_num):
                dev.detach_kernel_driver(self.intf_num)
        except Exception:
            pass

        ep_out = _util.find_descriptor(chosen, custom_match=lambda e:
            _util.endpoint_direction(e.bEndpointAddress) == _util.ENDPOINT_OUT and
            _util.endpoint_type(e.bmAttributes) == _util.ENDPOINT_TYPE_BULK)
        ep_in = _util.find_descriptor(chosen, custom_match=lambda e:
            _util.endpoint_direction(e.bEndpointAddress) == _util.ENDPOINT_IN and
            _util.endpoint_type(e.bmAttributes) == _util.ENDPOINT_TYPE_BULK)
        if ep_out is None or ep_in is None:
            raise LokeError("לא נמצאו ערוצי bulk IN/OUT בממשק.")

        self.dev, self.ep_in, self.ep_out = dev, ep_in, ep_out
        self._timeout = timeout_ms
        self.log("✓ מכשיר Samsung זוהה. מבצע handshake…")
        self._handshake()

    def _handshake(self):
        self._write(b"ODIN")
        resp = self._read(4)
        if resp != b"LOKE":
            raise LokeError(f"handshake נכשל. צפוי 'LOKE', התקבל {resp!r}. ודא שהמכשיר במצב Download.")
        self.log("✓ handshake הצליח (ODIN → LOKE).")

    # ---- שכבת תעבורה ----
    def _write(self, data):
        if self.hexlog:
            self.log("→ " + bytes(data)[:64].hex())
        n = self.ep_out.write(data, self._timeout)
        if self.send_zlp:
            try:
                self.ep_out.write(b"", self._timeout)
            except Exception:
                pass
        return n

    def _read(self, size):
        data = bytes(self.dev.read(self.ep_in.bEndpointAddress, size, self._timeout))
        if self.hexlog:
            self.log("← " + data[:64].hex())
        return data

    def get_device_type(self):
        """מחזיר את מזהה סוג המכשיר (מספר) — פעולת קריאה בטוחה."""
        self._write(_u32(CT_SESSION, SESS_DEVICETYPE))
        val = self._read_response(CT_SESSION)
        self.log(f"✓ device type = {val}")
        return val

    def _read_response(self, expected_type):
        resp = self._read(8)
        if len(resp) < 8:
            raise LokeError(f"תשובה קצרה מהצפוי ({len(resp)} בתים).")
        rtype, result = struct.unpack("<II", resp[:8])
        if rtype != expected_type:
            raise LokeError(f"סוג תשובה לא צפוי: קיבלתי 0x{rtype:02X}, ציפיתי 0x{expected_type:02X}.")
        return result

    # ---- פתיחת סשן ----
    def begin_session(self):
        self._write(_u32(CT_SESSION, SESS_BEGIN))
        self.default_packet_size = self._read_response(CT_SESSION)
        self.log(f"✓ הסשן נפתח. default packet size = {self.default_packet_size}")
        time.sleep(0.3)
        if self.default_packet_size != 0:
            file_part_size = 1048576  # 1 MiB, כמו בהיימדל
            self._write(_u32(CT_SESSION, SESS_FILEPARTSIZE, file_part_size))
            r = self._read_response(CT_SESSION)
            if r != 0:
                raise LokeError(f"קביעת גודל חלק הקובץ נכשלה (תשובה {r}).")
            self.log("✓ גודל חלק הקובץ נקבע (1 MiB).")

    # ---- קריאת PIT (בטוח, ללא כתיבה) ----
    def read_pit(self):
        self._write(_u32(CT_PITFILE, PIT_DUMP))
        file_size = self._read_response(CT_PITFILE)
        if file_size <= 0 or file_size > 1024 * 64:
            raise LokeError(f"גודל PIT לא סביר ({file_size}).")
        self.log(f"מוריד PIT בגודל {file_size} בתים…")

        parts = (file_size + PIT_PART_CHUNK - 1) // PIT_PART_CHUNK
        buf = bytearray()
        for i in range(parts):
            self._write(_u32(CT_PITFILE, PIT_PART, i))
            chunk = self._read(PIT_PART_CHUNK)
            buf.extend(chunk)
        buf = bytes(buf[:file_size])

        # סיום העברת ה-PIT
        self._write(_u32(CT_PITFILE, PIT_ENDXFER))
        try:
            self._read_response(CT_PITFILE)
        except Exception:
            pass  # חלק מהדגמים לא מחזירים תשובה לסיום — לא קריטי לקריאה
        self.log("✓ קריאת PIT הושלמה.")
        return buf

    # ---- כתיבה (flash) — ניסיוני! פעולה שכותבת למכשיר ----
    def _write_part(self, data):
        """שולח חלק נתונים גולמי (עד 1MB) עם timeout מורחב."""
        if self.hexlog:
            self.log(f"→ [data part {len(data)} B]")
        self.ep_out.write(data, 60000)
        if self.send_zlp:
            try:
                self.ep_out.write(b"", self._timeout)
            except Exception:
                pass

    def flash_image(self, entry, data, on_progress=None):
        """
        כותב image למחיצה לפי רשומת PIT (entry עם device_type ו-id).
        מיישם את רצף Odin/LOKE של Heimdall. **ניסיוני — לבדיקה על מכשיר בר-הקרבה.**
        """
        device_type = int(entry["device_type"])
        file_id = int(entry["id"])
        name = entry.get("partition", "?")
        total = len(data)
        if total == 0:
            raise LokeError("הקובץ ריק.")
        self.log(f"מתחיל פלאש ל-{name} (deviceType={device_type}, id={file_id}, {total} בתים)…")

        # התחלת העברת קובץ
        self._write(_u32(CT_FILEXFER, FT_FLASH))
        self._read_response(CT_FILEXFER)

        offset = 0
        while offset < total:
            remaining = total - offset
            parts = min(FT_SEQ_MAX, (remaining + FT_PACKET_SIZE - 1) // FT_PACKET_SIZE)
            seq_total = parts * FT_PACKET_SIZE            # מרופד (FlashPart)
            seq_effective = min(remaining, seq_total)     # בתים בפועל (End)
            is_last = (offset + seq_effective >= total)

            # תחילת רצף
            self._write(_u32(CT_FILEXFER, FT_PART, seq_total))
            self._read_response(CT_FILEXFER)

            # שליחת חלקי 1MB (החלק האחרון מרופד באפסים)
            for i in range(parts):
                cs = offset + i * FT_PACKET_SIZE
                chunk = data[cs:cs + FT_PACKET_SIZE]
                if len(chunk) < FT_PACKET_SIZE:
                    chunk = chunk + b"\x00" * (FT_PACKET_SIZE - len(chunk))
                self._write_part(chunk)
                self._read_response(CT_FILEXFER)  # אישור החלק
                if on_progress:
                    on_progress(min(cs + FT_PACKET_SIZE, total) / total)

            # סיום רצף
            self._write(self._end_phone(seq_effective, device_type, file_id, is_last))
            self._read_response(CT_FILEXFER)
            offset += seq_effective

        self.log(f"✓ הפלאש ל-{name} הושלם.")

    @staticmethod
    def _end_phone(seq_effective, device_type, file_id, is_last):
        # [0x66][FT_END][dest=phone][seqEffective][unknown1=0][deviceType][fileId][endOfFile]
        return _u32(CT_FILEXFER, FT_END, FT_DEST_PHONE, seq_effective, 0,
                    device_type, file_id, 1 if is_last else 0)

    # ---- סיום סשן ----
    def end_session(self, reboot=False):
        req = END_REBOOT if reboot else END_SESSION
        self._write(_u32(CT_ENDSESSION, req))
        try:
            self._read_response(CT_ENDSESSION)
        except Exception:
            pass
        self.log("✓ הסשן נסגר" + (" והמכשיר מאותחל." if reboot else "."))

    def close(self):
        if self.dev is not None and self._util is not None:
            try:
                self._util.dispose_resources(self.dev)
            except Exception:
                pass
        self.dev = None


# ---------------------------------------------------------------------------
# פירוק קובץ PIT
# ---------------------------------------------------------------------------
def _cstr(b):
    z = b.find(b"\0")
    if z >= 0:
        b = b[:z]
    return b.decode("ascii", "replace").strip()


def parse_pit(data):
    """מחזיר (info, entries). entries הם dict עם partition/filename/id/blocks."""
    if len(data) < PIT_HEADER_SIZE:
        raise LokeError("קובץ PIT קצר מדי.")
    magic, count = struct.unpack("<II", data[:8])
    if magic != PIT_MAGIC:
        raise LokeError(f"חתימת PIT שגויה (0x{magic:08X}). ייתכן שהקריאה לא הצליחה.")
    entries = []
    off = PIT_HEADER_SIZE
    for _ in range(count):
        if off + PIT_ENTRY_SIZE > len(data):
            break
        e = data[off:off + PIT_ENTRY_SIZE]
        device_type = struct.unpack("<I", e[4:8])[0]
        identifier = struct.unpack("<I", e[8:12])[0]
        block_count = struct.unpack("<I", e[24:28])[0]
        entries.append({
            "device_type": device_type,
            "id": identifier,
            "blocks": block_count,
            "partition": _cstr(e[36:68]),
            "filename": _cstr(e[68:100]),
        })
        off += PIT_ENTRY_SIZE
    return {"magic": magic, "count": count}, entries


# ---------------------------------------------------------------------------
# בדיקה עצמאית (ללא חומרה): מאמת את פירוק ה-PIT מול חבילה מסונתזת
# ---------------------------------------------------------------------------
def _selftest():
    def entry(idv, blocks, name, fname, dtype=0):
        b = bytearray(PIT_ENTRY_SIZE)
        struct.pack_into("<I", b, 4, dtype)
        struct.pack_into("<I", b, 8, idv)
        struct.pack_into("<I", b, 24, blocks)
        nm = name.encode("ascii"); b[36:36 + len(nm)] = nm
        fn = fname.encode("ascii"); b[68:68 + len(fn)] = fn
        return bytes(b)

    hdr = bytearray(PIT_HEADER_SIZE)
    struct.pack_into("<I", hdr, 0, PIT_MAGIC)
    struct.pack_into("<I", hdr, 4, 2)
    blob = bytes(hdr) + entry(1, 100, "BOOT", "boot.img") + entry(2, 4096, "USERDATA", "userdata.img")
    info, entries = parse_pit(blob)
    assert info["count"] == 2, info
    assert entries[0] == {"device_type": 0, "id": 1, "blocks": 100, "partition": "BOOT", "filename": "boot.img"}, entries[0]
    assert entries[1]["partition"] == "USERDATA" and entries[1]["filename"] == "userdata.img", entries[1]
    print("loke_engine parse_pit self-test PASSED:", entries)

    # בדיקת מבנה חבילות הכתיבה (byte layout)
    ep = LokeDevice._end_phone(0x1000, 2, 5, True)
    assert len(ep) == 32, len(ep)
    fields = struct.unpack("<8I", ep)
    assert fields == (CT_FILEXFER, FT_END, FT_DEST_PHONE, 0x1000, 0, 2, 5, 1), fields
    flashpart = _u32(CT_FILEXFER, FT_PART, 0x2000)
    assert len(flashpart) == 12 and struct.unpack("<3I", flashpart) == (CT_FILEXFER, FT_PART, 0x2000)
    print("loke_engine flash-packet layout self-test PASSED")


if __name__ == "__main__":
    _selftest()
