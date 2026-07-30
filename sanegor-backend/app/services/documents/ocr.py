"""OCR for Hebrew and English, with image pre-processing.

Tesseract's Hebrew model is sensitive to input quality, so scans are corrected
before recognition: grayscale → deskew → adaptive threshold → denoise, and
up-scaling for low-resolution images.  Tables are detected structurally (via
morphological line extraction) rather than trusted to the text layout, because
Tesseract's reading order is unreliable for RTL tables.
"""

from __future__ import annotations

import asyncio
import io
from dataclasses import dataclass
from typing import TYPE_CHECKING

from app.core.errors import FeatureDisabledError, ValidationError
from app.core.logging import get_logger

if TYPE_CHECKING:
    from app.services.documents.extractor import ExtractionResult

logger = get_logger(__name__)

# Tesseract's page-segmentation mode 6 ("assume a single uniform block of
# text") beats the default on scanned legal documents, which are dense prose.
_TESSERACT_CONFIG = "--oem 1 --psm 6"
_MIN_OCR_WIDTH = 1200


@dataclass(slots=True)
class OcrPageResult:
    """OCR output for a single page or image."""

    text: str
    confidence: float = 0.0
    tables_detected: int = 0


class OcrService:
    """Runs Tesseract over images and scanned PDFs."""

    def __init__(
        self,
        *,
        enabled: bool = True,
        languages: str = "heb+eng",
        tesseract_cmd: str = "/usr/bin/tesseract",
    ) -> None:
        self._enabled = enabled
        self._languages = languages
        self._tesseract_cmd = tesseract_cmd
        self._available: bool | None = None

    # ------------------------------------------------------------ availability
    def _ensure_available(self) -> None:
        if not self._enabled:
            raise FeatureDisabledError("OCR אינו מופעל בשרת זה")
        if self._available is None:
            self._available = self._probe()
        if not self._available:
            raise FeatureDisabledError("מנוע ה-OCR אינו מותקן בשרת")

    def _probe(self) -> bool:
        try:
            import pytesseract

            pytesseract.pytesseract.tesseract_cmd = self._tesseract_cmd
            version = pytesseract.get_tesseract_version()
            installed = set(pytesseract.get_languages(config=""))
            missing = {lang for lang in self._languages.split("+") if lang not in installed}
            if missing:
                logger.warning("ocr_languages_missing", missing=sorted(missing))
            logger.info("ocr_available", version=str(version))
            return True
        except Exception as exc:
            logger.warning("ocr_unavailable", error=str(exc))
            return False

    @property
    def enabled(self) -> bool:
        return self._enabled

    # --------------------------------------------------------------- public API
    async def extract_from_image(self, data: bytes) -> ExtractionResult:
        """OCR a single image."""
        self._ensure_available()
        page = await asyncio.to_thread(self._ocr_image_bytes, data)
        from app.services.documents.extractor import ExtractionResult

        warnings: list[str] = ["הטקסט חולץ באמצעות OCR"]
        if page.confidence and page.confidence < 60:
            warnings.append("איכות הסריקה נמוכה — מומלץ לצלם שוב באור טוב")
        if page.tables_detected:
            warnings.append(f"זוהו {page.tables_detected} טבלאות במסמך")
        return ExtractionResult(text=page.text, page_count=1, used_ocr=True, warnings=warnings)

    async def extract_from_pdf(self, data: bytes, *, max_pages: int = 40) -> ExtractionResult:
        """Rasterise a scanned PDF page by page and OCR each page."""
        self._ensure_available()
        images = await asyncio.to_thread(self._pdf_to_images, data, max_pages)
        if not images:
            raise ValidationError("לא ניתן להמיר את קובץ ה-PDF לתמונות עבור OCR")

        pages = await asyncio.gather(
            *(asyncio.to_thread(self._ocr_image_bytes, image) for image in images)
        )
        from app.services.documents.extractor import ExtractionResult

        text = "\n\n".join(f"--- עמוד {i} ---\n{p.text}" for i, p in enumerate(pages, 1) if p.text)
        confidences = [p.confidence for p in pages if p.confidence]
        average = sum(confidences) / len(confidences) if confidences else 0.0

        warnings = ["הטקסט חולץ באמצעות OCR"]
        if average and average < 60:
            warnings.append("איכות הסריקה נמוכה — ייתכנו שגיאות בזיהוי")
        return ExtractionResult(text=text, page_count=len(pages), used_ocr=True, warnings=warnings)

    # --------------------------------------------------------------- internals
    @staticmethod
    def _pdf_to_images(data: bytes, max_pages: int) -> list[bytes]:
        """Rasterise PDF pages to PNG bytes.

        Uses PyMuPDF when present (fast, no external binary) and otherwise
        shells out to ``pdftoppm`` from poppler-utils, which the Docker image
        installs.  Returns an empty list when neither is available.
        """
        try:
            import fitz  # PyMuPDF
        except ImportError:
            return OcrService._pdf_to_images_poppler(data, max_pages)

        images: list[bytes] = []
        with fitz.open(stream=data, filetype="pdf") as document:
            for page in document[:max_pages]:
                # 200 DPI: enough for Hebrew glyphs without exploding memory.
                pixmap = page.get_pixmap(dpi=200)
                images.append(pixmap.tobytes("png"))
        return images

    @staticmethod
    def _pdf_to_images_poppler(data: bytes, max_pages: int) -> list[bytes]:
        import shutil
        import subprocess
        import tempfile
        from pathlib import Path

        binary = shutil.which("pdftoppm")
        if binary is None:
            logger.warning("pdf_rasterise_unavailable", detail="install PyMuPDF or poppler-utils")
            return []

        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "input.pdf"
            source.write_bytes(data)
            try:
                subprocess.run(  # noqa: S603 - argv list, no shell interpolation
                    [
                        binary,
                        "-png",
                        "-r",
                        "200",
                        "-f",
                        "1",
                        "-l",
                        str(max_pages),
                        str(source),
                        str(Path(tmp) / "page"),
                    ],
                    check=True,
                    capture_output=True,
                    timeout=180,
                )
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
                logger.error("pdf_rasterise_failed", error=str(exc))
                return []
            return [p.read_bytes() for p in sorted(Path(tmp).glob("page*.png"))]

    def _ocr_image_bytes(self, data: bytes) -> OcrPageResult:
        import pytesseract
        from PIL import Image, UnidentifiedImageError

        pytesseract.pytesseract.tesseract_cmd = self._tesseract_cmd
        try:
            image = Image.open(io.BytesIO(data))
            image.load()
        except (UnidentifiedImageError, OSError) as exc:
            raise ValidationError("קובץ התמונה פגום או אינו נתמך") from exc

        prepared, tables = self._preprocess(image)
        try:
            data_frame = pytesseract.image_to_data(
                prepared,
                lang=self._languages,
                config=_TESSERACT_CONFIG,
                output_type=pytesseract.Output.DICT,
            )
        except pytesseract.TesseractError as exc:
            logger.error("ocr_failed", error=str(exc))
            raise ValidationError("זיהוי הטקסט נכשל") from exc

        words: list[str] = []
        confidences: list[float] = []
        for word, confidence in zip(
            data_frame.get("text", []), data_frame.get("conf", []), strict=False
        ):
            if not word.strip():
                continue
            words.append(word)
            try:
                value = float(confidence)
            except (TypeError, ValueError):
                continue
            if value >= 0:
                confidences.append(value)

        return OcrPageResult(
            text=" ".join(words),
            confidence=sum(confidences) / len(confidences) if confidences else 0.0,
            tables_detected=tables,
        )

    def _preprocess(self, image: object) -> tuple[object, int]:
        """Enhance a scan for recognition and count table-like regions.

        Falls back to the untouched image when OpenCV/NumPy are unavailable,
        so OCR still works (less accurately) on a minimal install.
        """
        try:
            import cv2
            import numpy as np
            from PIL import Image
        except ImportError:  # pragma: no cover - optional dependency
            logger.warning("ocr_preprocessing_skipped", reason="opencv/numpy missing")
            return image, 0

        array = np.array(image.convert("RGB"))  # type: ignore[attr-defined]
        gray = cv2.cvtColor(array, cv2.COLOR_RGB2GRAY)

        # Up-scale small scans: Tesseract wants ~300 DPI equivalent.
        height, width = gray.shape[:2]
        if width < _MIN_OCR_WIDTH:
            scale = _MIN_OCR_WIDTH / width
            gray = cv2.resize(
                gray, (int(width * scale), int(height * scale)), interpolation=cv2.INTER_CUBIC
            )

        gray = self._deskew(gray, cv2, np)
        gray = cv2.fastNlMeansDenoising(gray, None, h=10, templateWindowSize=7, searchWindowSize=21)
        binary = cv2.adaptiveThreshold(
            gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 11
        )
        tables = self._count_tables(binary, cv2, np)
        return Image.fromarray(binary), tables

    @staticmethod
    def _deskew(gray: object, cv2: object, np: object) -> object:
        """Rotate the page so text lines are horizontal."""
        inverted = cv2.bitwise_not(gray)  # type: ignore[attr-defined]
        _, threshold = cv2.threshold(  # type: ignore[attr-defined]
            inverted,
            0,
            255,
            cv2.THRESH_BINARY | cv2.THRESH_OTSU,  # type: ignore[attr-defined]
        )
        coords = np.column_stack(np.where(threshold > 0))  # type: ignore[attr-defined]
        if coords.size == 0:
            return gray

        angle = cv2.minAreaRect(coords)[-1]  # type: ignore[attr-defined]
        angle = -(90 + angle) if angle < -45 else -angle
        if abs(angle) < 0.3 or abs(angle) > 20:  # ignore noise and false positives
            return gray

        height, width = gray.shape[:2]  # type: ignore[attr-defined]
        matrix = cv2.getRotationMatrix2D((width / 2, height / 2), angle, 1.0)  # type: ignore[attr-defined]
        return cv2.warpAffine(  # type: ignore[attr-defined]
            gray,
            matrix,
            (width, height),
            flags=cv2.INTER_CUBIC,  # type: ignore[attr-defined]
            borderMode=cv2.BORDER_REPLICATE,  # type: ignore[attr-defined]
        )

    @staticmethod
    def _count_tables(binary: object, cv2: object, np: object) -> int:
        """Count grid-like regions via horizontal/vertical line intersection."""
        inverted = cv2.bitwise_not(binary)  # type: ignore[attr-defined]
        height, width = inverted.shape[:2]  # type: ignore[attr-defined]

        horizontal_kernel = cv2.getStructuringElement(  # type: ignore[attr-defined]
            cv2.MORPH_RECT,
            (max(width // 30, 10), 1),  # type: ignore[attr-defined]
        )
        vertical_kernel = cv2.getStructuringElement(  # type: ignore[attr-defined]
            cv2.MORPH_RECT,
            (1, max(height // 30, 10)),  # type: ignore[attr-defined]
        )
        horizontal = cv2.morphologyEx(inverted, cv2.MORPH_OPEN, horizontal_kernel, iterations=2)  # type: ignore[attr-defined]
        vertical = cv2.morphologyEx(inverted, cv2.MORPH_OPEN, vertical_kernel, iterations=2)  # type: ignore[attr-defined]

        grid = cv2.bitwise_and(horizontal, vertical)  # type: ignore[attr-defined]
        contours, _ = cv2.findContours(  # type: ignore[attr-defined]
            grid,
            cv2.RETR_EXTERNAL,
            cv2.CHAIN_APPROX_SIMPLE,  # type: ignore[attr-defined]
        )
        # A table needs several line intersections; a few stray marks do not.
        return 1 if len(contours) >= 4 else 0
