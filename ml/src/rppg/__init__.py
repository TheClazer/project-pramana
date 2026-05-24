"""Python rPPG prototype. The Kotlin port (rppg/RealRppgStream.kt) MUST
produce numerically equivalent SNR values for the same input.

Bible §6: POS algorithm + 30-frame sliding window + FFT bandpass 0.75-3.0 Hz.
"""
