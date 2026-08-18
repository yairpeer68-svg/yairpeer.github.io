package com.magen.family.service.vpn;

import android.content.Context;

import java.net.DatagramSocket;
import java.net.Socket;

/**
 * TunBridge — הממשק שדרכו המנוע מדבר עם VpnService.
 *
 * מופרד לממשק כדי שהמנוע (VpnEngine/TcpRelay/UdpRelay) לא יהיה תלוי ישירות
 * ב-MagenVpnService — כך אפשר לבדוק אותו ולהחליף מימוש בלי לגעת בלוגיקה.
 */
public interface TunBridge {

    /**
     * מוציא socket מהמנהרה. **חובה** לכל socket יוצא — בלי זה החבילה שאנחנו
     * שולחים חוזרת לתוך ה-TUN ונוצרת לולאה אינסופית שמקפיאה את הרשת.
     */
    boolean protect(Socket socket);

    boolean protect(DatagramSocket socket);

    /** מזריק חבילת IP גולמית בחזרה למכשיר. חייב להיות בטוח ל-thread. */
    void writeToTun(byte[] packet, int length);

    Context context();
}
