package com.nexusops.platform.cli;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/** Renders a QR code with Unicode half blocks, black on white, so authenticator apps can scan it from a terminal. */
final class QrCodes {

    private static final int QUIET_ZONE = 2;
    private static final String COLORS = "\u001b[30;47m";
    private static final String RESET = "\u001b[0m";

    private QrCodes() {}

    static String render(String text) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            throw new IllegalStateException("Cannot render QR code", e);
        }
        StringBuilder out = new StringBuilder();
        int size = matrix.getWidth();
        for (int y = -QUIET_ZONE; y < size + QUIET_ZONE; y += 2) {
            out.append(COLORS);
            for (int x = -QUIET_ZONE; x < size + QUIET_ZONE; x++) {
                boolean top = dark(matrix, x, y);
                boolean bottom = dark(matrix, x, y + 1);
                out.append(top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ');
            }
            out.append(RESET).append('\n');
        }
        return out.toString();
    }

    private static boolean dark(BitMatrix matrix, int x, int y) {
        return x >= 0 && y >= 0 && x < matrix.getWidth() && y < matrix.getHeight() && matrix.get(x, y);
    }
}
