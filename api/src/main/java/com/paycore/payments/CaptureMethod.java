package com.paycore.payments;

/** {@code automatic}: capture immediately after authorization. {@code manual}: merchant calls /capture later. */
public enum CaptureMethod {
    AUTOMATIC, MANUAL;

    public String wire() {
        return name().toLowerCase();
    }

    public static CaptureMethod fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
