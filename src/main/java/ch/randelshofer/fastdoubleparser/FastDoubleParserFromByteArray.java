/*
 * @(#)FastDoubleParser.java
 * Copyright © 2021. Werner Randelshofer, Switzerland. MIT License.
 */

package ch.randelshofer.fastdoubleparser;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * This is a C++ to Java port of Daniel Lemire's fast_double_parser.
 * <p>
 * The code has been changed, so that it parses the same syntax as
 * {@link Double#parseDouble(String)}.
 * <p>
 * References:
 * <dl>
 *     <dt>Daniel Lemire, fast_double_parser, 4x faster than strtod.
 *     Apache License 2.0 or Boost Software License.</dt>
 *     <dd><a href="https://github.com/lemire/fast_double_parser">github.com</a></dd>
 *
 *     <dt>Daniel Lemire, fast_float number parsing library: 4x faster than strtod.
 *     Apache License 2.0.</dt>
 *     <dd><a href="https://github.com/fastfloat/fast_float">github.com</a></dd>
 *
 *     <dt>Daniel Lemire, Number Parsing at a Gigabyte per Second,
 *     Software: Practice and Experience 51 (8), 2021.
 *     arXiv.2101.11408v3 [cs.DS] 24 Feb 2021</dt>
 *     <dd><a href="https://arxiv.org/pdf/2101.11408.pdf">arxiv.org</a></dd>
 * </dl>
 */
public class FastDoubleParserFromByteArray {
    private final static long MINIMAL_NINETEEN_DIGIT_INTEGER = 1000_00000_00000_00000L;
    private final static int MINIMAL_EIGHT_DIGIT_INTEGER = 10_000_000;
    /**
     * Special value in {@link #CHAR_TO_HEX_MAP} for
     * the decimal point character.
     */
    private static final byte DECIMAL_POINT_CLASS = -4;
    /**
     * Special value in {@link #CHAR_TO_HEX_MAP} for
     * characters that are neither a hex digit nor
     * a decimal point character..
     */
    private static final byte OTHER_CLASS = -1;
    /**
     * A table of 128 entries or of entries up to including
     * character 'p' would suffice.
     * <p>
     * However for some reason, performance is best,
     * if this table has exactly 256 entries.
     */
    private static final byte[] CHAR_TO_HEX_MAP = new byte[256];
    private final static VarHandle readLongFromByteArray =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static {
        for (char ch = 0; ch < CHAR_TO_HEX_MAP.length; ch++) {
            CHAR_TO_HEX_MAP[ch] = OTHER_CLASS;
        }
        for (char ch = '0'; ch <= '9'; ch++) {
            CHAR_TO_HEX_MAP[ch] = (byte) (ch - '0');
        }
        for (char ch = 'A'; ch <= 'F'; ch++) {
            CHAR_TO_HEX_MAP[ch] = (byte) (ch - 'A' + 10);
        }
        for (char ch = 'a'; ch <= 'f'; ch++) {
            CHAR_TO_HEX_MAP[ch] = (byte) (ch - 'a' + 10);
        }
        for (char ch = '.'; ch <= '.'; ch++) {
            CHAR_TO_HEX_MAP[ch] = DECIMAL_POINT_CLASS;
        }
    }

    /**
     * Prevents instantiation.
     */
    private FastDoubleParserFromByteArray() {

    }

    private static boolean isInteger(byte c) {
        return (byte) '0' <= c && c <= (byte) '9';
    }

    private static boolean isMadeOfEightDigits(long val) {
        long l = ((val + 0x4646464646464646L) | (val - 0x3030303030303030L)) &
                0x8080808080808080L;
        return l == 0L;
    }

    private static NumberFormatException newNumberFormatException(byte[] str, int off, int len) {
        if (len > 1024) {
            // str can be up to Integer.MAX_VALUE characters long
            return new NumberFormatException("For input string of length " + len);
        } else {
            return new NumberFormatException("For input string: \"" + new String(str, off, len, StandardCharsets.ISO_8859_1) + "\"");
        }
    }

    /**
     * Convenience method for calling {@link #parseDouble(byte[], int, int)}.
     *
     * @param str the string to be parsed, a byte array with characters
     *            in ISO-8859-1, ASCII or UTF-8 encoding
     * @return the parsed double value
     * @throws NumberFormatException if the string can not be parsed
     */
    public static double parseDouble(byte[] str) throws NumberFormatException {
        return parseDouble(str, 0, str.length);
    }

    /**
     * Returns a Double object holding the double value represented by the
     * argument string {@code str}.
     * <p>
     * This method can be used as a drop in for method
     * {@link Double#valueOf(String)}. (Assuming that the API of this method
     * has not changed since Java SE 16).
     * <p>
     * Leading and trailing whitespace characters in {@code str} are ignored.
     * Whitespace is removed as if by the {@link String#trim()} method;
     * that is, characters in the range [U+0000,U+0020].
     * <p>
     * The rest of {@code str} should constitute a FloatValue as described by the
     * lexical syntax rules shown below:
     * <blockquote>
     * <dl>
     * <dt><i>FloatValue:</i>
     * <dd><i>[Sign]</i> {@code NaN}
     * <dd><i>[Sign]</i> {@code Infinity}
     * <dd><i>[Sign] DecimalFloatingPointLiteral</i>
     * <dd><i>[Sign] HexFloatingPointLiteral</i>
     * <dd><i>SignedInteger</i>
     * </dl>
     *
     * <dl>
     * <dt><i>HexFloatingPointLiteral</i>:
     * <dd><i>HexSignificand BinaryExponent</i>
     * </dl>
     *
     * <dl>
     * <dt><i>HexSignificand:</i>
     * <dd><i>HexNumeral</i>
     * <dd><i>HexNumeral</i> {@code .}
     * <dd>{@code 0x} <i>[HexDigits]</i> {@code .} <i>HexDigits</i>
     * <dd>{@code 0X} <i>[HexDigits]</i> {@code .} <i>HexDigits</i>
     * </dl>
     *
     * <dl>
     * <dt><i>HexSignificand:</i>
     * <dd><i>HexNumeral</i>
     * <dd><i>HexNumeral</i> {@code .}
     * <dd>{@code 0x} <i>[HexDigits]</i> {@code .} <i>HexDigits</i>
     * <dd>{@code 0X} <i>[HexDigits]</i> {@code .} <i>HexDigits</i>
     * </dl>
     *
     * <dl>
     * <dt><i>BinaryExponent:</i>
     * <dd><i>BinaryExponentIndicator SignedInteger</i>
     * </dl>
     *
     * <dl>
     * <dt><i>BinaryExponentIndicator:</i>
     * <dd>{@code p}
     * <dd>{@code P}
     * </dl>
     *
     * <dl>
     * <dt><i>DecimalFloatingPointLiteral:</i>
     * <dd><i>Digits {@code .} [Digits] [ExponentPart]</i>
     * <dd><i>{@code .} Digits [ExponentPart]</i>
     * <dd><i>Digits ExponentPart</i>
     * </dl>
     *
     * <dl>
     * <dt><i>ExponentPart:</i>
     * <dd><i>ExponentIndicator SignedInteger</i>
     * </dl>
     *
     * <dl>
     * <dt><i>ExponentIndicator:</i>
     * <dd><i>(one of)</i>
     * <dd><i>e E</i>
     * </dl>
     *
     * <dl>
     * <dt><i>SignedInteger:</i>
     * <dd><i>[Sign] Digits</i>
     * </dl>
     *
     * <dl>
     * <dt><i>Sign:</i>
     * <dd><i>(one of)</i>
     * <dd><i>+ -</i>
     * </dl>
     *
     * <dl>
     * <dt><i>Digits:</i>
     * <dd><i>Digit {Digit}</i>
     * </dl>
     *
     * <dl>
     * <dt><i>HexNumeral:</i>
     * <dd>{@code 0} {@code x} <i>HexDigits</i>
     * <dd>{@code 0} {@code X} <i>HexDigits</i>
     * </dl>
     *
     * <dl>
     * <dt><i>HexDigits:</i>
     * <dd><i>HexDigit {HexDigit}</i>
     * </dl>
     *
     * <dl>
     * <dt><i>HexDigit:</i>
     * <dd><i>(one of)</i>
     * <dd>{@code 0 1 2 3 4 5 6 7 8 9 a b c d e f A B C D E F}
     * </dl>
     * </blockquote>
     *
     * @param str the string to be parsed, a byte array with characters
     *            in ISO-8859-1, ASCII or UTF-8 encoding
     * @param off The index of the first byte to parse
     * @param len The number of bytes to parse
     * @return the parsed double value
     * @throws NumberFormatException if the string can not be parsed
     */
    public static double parseDouble(byte[] str, int off, int len) throws NumberFormatException {
        final int endIndex = len + off;

        // Skip leading whitespace
        // -------------------
        int index = skipWhitespace(str, off, endIndex);
        if (index == endIndex) {
            throw new NumberFormatException("empty String");
        }
        byte ch = str[index];

        // Parse optional sign
        // -------------------
        final boolean isNegative = ch == '-';
        if (isNegative || ch == '+') {
            ch = ++index < endIndex ? str[index] : 0;
            if (ch == 0) {
                throw newNumberFormatException(str, off, len);
            }
        }

        // Parse NaN or Infinity
        // ---------------------
        if (ch == 'N') {
            return parseNaN(str, index, endIndex, off);
        } else if (ch == 'I') {
            return parseInfinity(str, index, endIndex, isNegative, off);
        }

        // Parse optional leading zero
        // ---------------------------
        final boolean hasLeadingZero = ch == '0';
        if (hasLeadingZero) {
            ch = ++index < endIndex ? str[index] : 0;
            if (ch == 'x' || ch == 'X') {
                return parseRestOfHexFloatingPointLiteral(str, index + 1, endIndex, isNegative, off);
            }
        }

        return parseRestOfDecimalFloatLiteral(str, endIndex, index, isNegative, hasLeadingZero, off);
    }

    private static int parseEightDigits(long val) {
        long mask = 0x000000FF000000FFL;
        long mul1 = 0x000F424000000064L; // 100 + (1000000ULL << 32)
        long mul2 = 0x0000271000000001L; // 1 + (10000ULL << 32)
        val -= 0x3030303030303030L;
        val = (val * 10) + (val >>> 8); // val = (val * 2561) >> 8;
        val = (((val & mask) * mul1) + (((val >>> 16) & mask) * mul2)) >>> 32;
        return (int) (val);
    }

    private static double parseInfinity(byte[] str, int index, int endIndex, boolean negative, int off) {
        if (index + 7 < endIndex
                //  && str.charAt(index) == 'I'
                && str[index + 1] == (byte) 'n'
                && str[index + 2] == (byte) 'f'
                && str[index + 3] == (byte) 'i'
                && str[index + 4] == (byte) 'n'
                && str[index + 5] == (byte) 'i'
                && str[index + 6] == (byte) 't'
                && str[index + 7] == (byte) 'y'
        ) {
            index = skipWhitespace(str, index + 8, endIndex);
            if (index < endIndex) {
                throw newNumberFormatException(str, off, endIndex - off);
            }
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        } else {
            throw newNumberFormatException(str, off, endIndex - off);
        }
    }

    private static double parseNaN(byte[] str, int index, int endIndex, int off) {
        if (index + 2 < endIndex
                //   && str.charAt(index) == 'N'
                && str[index + 1] == (byte) 'a'
                && str[index + 2] == (byte) 'N') {

            index = skipWhitespace(str, index + 3, endIndex);
            if (index < endIndex) {
                throw newNumberFormatException(str, off, endIndex - off);
            }

            return Double.NaN;
        } else {
            throw newNumberFormatException(str, off, endIndex - off);
        }
    }

    /**
     * Parses the following rules
     * (more rules are defined in {@link #parseDouble}):
     * <dl>
     * <dt><i>RestOfDecimalFloatingPointLiteral</i>:
     * <dd><i>[Digits] {@code .} [Digits] [ExponentPart]</i>
     * <dd><i>{@code .} Digits [ExponentPart]</i>
     * <dd><i>[Digits] ExponentPart</i>
     * </dl>
     *
     * @param str            the input string
     * @param endIndex       the length of the string
     * @param index          index to the first character of RestOfHexFloatingPointLiteral
     * @param isNegative     if the resulting number is negative
     * @param hasLeadingZero if the digit '0' has been consumed
     * @return a double representation
     */
    private static double parseRestOfDecimalFloatLiteral(byte[] str, int endIndex, int index, boolean isNegative, boolean hasLeadingZero, int off) {
        // Parse digits
        // ------------
        // Note: a multiplication by a constant is cheaper than an
        //       arbitrary integer multiplication.
        long digits = 0;// digits is treated as an unsigned long
        int exponent = 0;
        final int indexOfFirstDigit = index;
        int virtualIndexOfPoint = -1;
        final int digitCount;
        byte ch = 0;
        for (; index < endIndex; index++) {
            ch = str[index];
            if (isInteger(ch)) {
                // This might overflow, we deal with it later.
                digits = 10 * digits + ch - '0';
            } else if (ch == '.') {
                if (virtualIndexOfPoint != -1) {
                    throw newNumberFormatException(str, off, endIndex - off);
                }
                virtualIndexOfPoint = index;
                while (index < endIndex - 9) {
                    long val = (long) readLongFromByteArray.get(str, index + 1);
                    if (isMadeOfEightDigits(val)) {
                        // This might overflow, we deal with it later.
                        digits = digits * 100_000_000L + parseEightDigits(val);
                        index += 8;
                    } else {
                        break;
                    }
                }
            } else {
                break;
            }
        }
        final int indexAfterDigits = index;
        if (virtualIndexOfPoint == -1) {
            digitCount = indexAfterDigits - indexOfFirstDigit;
            virtualIndexOfPoint = indexAfterDigits;
        } else {
            digitCount = indexAfterDigits - indexOfFirstDigit - 1;
            exponent = virtualIndexOfPoint - index + 1;
        }

        // Parse exponent number
        // ---------------------
        long exp_number = 0;
        final boolean hasExponent = (ch == 'e') || (ch == 'E');
        if (hasExponent) {
            ch = ++index < endIndex ? str[index] : 0;
            boolean neg_exp = ch == '-';
            if (neg_exp || ch == '+') {
                ch = ++index < endIndex ? str[index] : 0;
            }
            if (!isInteger(ch)) {
                throw newNumberFormatException(str, off, endIndex - off);
            }
            do {
                // Guard against overflow of exp_number
                if (exp_number < MINIMAL_EIGHT_DIGIT_INTEGER) {
                    exp_number = 10 * exp_number + ch - '0';
                }
                ch = ++index < endIndex ? str[index] : 0;
            } while (isInteger(ch));
            if (neg_exp) {
                exp_number = -exp_number;
            }
            exponent += exp_number;
        }

        // Skip trailing whitespace
        // ------------------------
        index = skipWhitespace(str, index, endIndex);
        if (index < endIndex
                || !hasLeadingZero && digitCount == 0 && str[virtualIndexOfPoint] != '.') {
            throw newNumberFormatException(str, off, endIndex - off);
        }

        // Re-parse digits in case of a potential overflow
        // -----------------------------------------------
        final boolean isDigitsTruncated;
        int skipCountInTruncatedDigits = 0;//counts +1 if we skipped over the decimal point
        if (digitCount > 19) {
            digits = 0;
            for (index = indexOfFirstDigit; index < indexAfterDigits; index++) {
                ch = str[index];
                if (ch == '.') {
                    skipCountInTruncatedDigits++;
                } else {
                    if (Long.compareUnsigned(digits, MINIMAL_NINETEEN_DIGIT_INTEGER) < 0) {
                        digits = 10 * digits + ch - '0';
                    } else {
                        break;
                    }
                }
            }
            isDigitsTruncated = index < indexAfterDigits;
        } else {
            isDigitsTruncated = false;
        }

        Double result = FastDoubleMath.decFloatLiteralToDouble(index, isNegative, digits, exponent, virtualIndexOfPoint, exp_number, isDigitsTruncated, skipCountInTruncatedDigits);
        if (result == null) {
            return parseRestOfDecimalFloatLiteralTheHardWay(str, off, endIndex - off);
        }
        return result;
    }

    /**
     * Parses the following rules
     * (more rules are defined in {@link #parseDouble}):
     * <dl>
     * <dt><i>RestOfDecimalFloatingPointLiteral</i>:
     * <dd><i>[Digits] {@code .} [Digits] [ExponentPart]</i>
     * <dd><i>{@code .} Digits [ExponentPart]</i>
     * <dd><i>[Digits] ExponentPart</i>
     * </dl>
     *  @param str            the input string
     */
    private static double parseRestOfDecimalFloatLiteralTheHardWay(byte[] str, int off, int len) {
        return Double.parseDouble(new String(str, off, len, StandardCharsets.ISO_8859_1));
    }

    /**
     * Parses the following rules
     * (more rules are defined in {@link #parseDouble}):
     * <dl>
     * <dt><i>RestOfHexFloatingPointLiteral</i>:
     * <dd><i>RestOfHexSignificand BinaryExponent</i>
     * </dl>
     *
     * <dl>
     * <dt><i>RestOfHexSignificand:</i>
     * <dd><i>HexDigits</i>
     * <dd><i>HexDigits</i> {@code .}
     * <dd><i>[HexDigits]</i> {@code .} <i>HexDigits</i>
     * </dl>
     *
     * @param str        the input string
     * @param index      index to the first character of RestOfHexFloatingPointLiteral
     * @param endIndex   the end index of the string
     * @param isNegative if the resulting number is negative
     * @param off        offset from the start where character of interest start
     * @return a double representation
     */
    private static double parseRestOfHexFloatingPointLiteral(
            byte[] str, int index, int endIndex, boolean isNegative, int off) {
        if (index >= endIndex) {
            throw newNumberFormatException(str, off, endIndex - off);
        }

        // Parse digits
        // ------------
        long digits = 0;// digits is treated as an unsigned long
        int exponent = 0;
        final int indexOfFirstDigit = index;
        int virtualIndexOfPoint = -1;
        final int digitCount;
        byte ch = 0;
        for (; index < endIndex; index++) {
            ch = str[index];
            // Table look up is faster than a sequence of if-else-branches.
            int hexValue = ch < 0 ? OTHER_CLASS : CHAR_TO_HEX_MAP[ch];
            if (hexValue >= 0) {
                digits = (digits << 4) | hexValue;// This might overflow, we deal with it later.
            } else if (hexValue == DECIMAL_POINT_CLASS) {
                if (virtualIndexOfPoint != -1) {
                    throw newNumberFormatException(str, off, endIndex - off);
                }
                virtualIndexOfPoint = index;
            } else {
                break;
            }
        }
        final int indexAfterDigits = index;
        if (virtualIndexOfPoint == -1) {
            digitCount = indexAfterDigits - indexOfFirstDigit;
            virtualIndexOfPoint = indexAfterDigits;
        } else {
            digitCount = indexAfterDigits - indexOfFirstDigit - 1;
            exponent = Math.min(virtualIndexOfPoint - index + 1, MINIMAL_EIGHT_DIGIT_INTEGER) * 4;
        }

        // Parse exponent number
        // ---------------------
        long exp_number = 0;
        final boolean hasExponent = (ch == 'p') || (ch == 'P');
        if (hasExponent) {
            ch = ++index < endIndex ? str[index] : 0;
            boolean neg_exp = ch == '-';
            if (neg_exp || ch == '+') {
                ch = ++index < endIndex ? str[index] : 0;
            }
            if (!isInteger(ch)) {
                throw newNumberFormatException(str, off, endIndex - off);
            }
            do {
                // Guard against overflow of exp_number
                if (exp_number < MINIMAL_EIGHT_DIGIT_INTEGER) {
                    exp_number = 10 * exp_number + ch - '0';
                }
                ch = ++index < endIndex ? str[index] : 0;
            } while (isInteger(ch));
            if (neg_exp) {
                exp_number = -exp_number;
            }
            exponent += exp_number;
        }

        // Skip trailing whitespace
        // ------------------------
        index = skipWhitespace(str, index, endIndex);
        if (index < endIndex
                || digitCount == 0 && str[virtualIndexOfPoint] != '.'
                || !hasExponent) {
            throw newNumberFormatException(str, off, endIndex - off);
        }

        // Re-parse digits in case of a potential overflow
        // -----------------------------------------------
        final boolean isDigitsTruncated;
        int skipCountInTruncatedDigits = 0;//counts +1 if we skipped over the decimal point
        if (digitCount > 16) {
            digits = 0;
            for (index = indexOfFirstDigit; index < indexAfterDigits; index++) {
                ch = str[index];
                // Table look up is faster than a sequence of if-else-branches.
                int hexValue = ch < 0 ? OTHER_CLASS : CHAR_TO_HEX_MAP[ch];
                if (hexValue >= 0) {
                    if (Long.compareUnsigned(digits, MINIMAL_NINETEEN_DIGIT_INTEGER) < 0) {
                        digits = (digits << 4) | hexValue;
                    } else {
                        break;
                    }
                } else {
                    skipCountInTruncatedDigits++;
                }
            }
            isDigitsTruncated = (index < indexAfterDigits);
        } else {
            isDigitsTruncated = false;
        }

        Double d = FastDoubleMath.hexFloatLiteralToDouble(index, isNegative, digits, exponent, virtualIndexOfPoint, exp_number, isDigitsTruncated, skipCountInTruncatedDigits);
        return d == null ? Double.parseDouble(new String(str, off, endIndex - off)) : d;
    }

    private static int skipWhitespace(byte[] str, int index, int endIndex) {
        for (; index < endIndex; index++) {
            if ((str[index] & 0xff) > 0x20) {
                break;
            }
        }
        return index;
    }

}