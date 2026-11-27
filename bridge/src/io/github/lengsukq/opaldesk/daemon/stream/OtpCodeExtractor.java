package io.github.lengsukq.opaldesk.daemon.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java SMS/notification verification code (OTP) extractor supporting Chinese and English
 * 4-8 digit codes while aggressively suppressing false positives from monetary amounts,
 * phone/hotline numbers, years/dates, durations, and card/order suffixes.
 */
public final class OtpCodeExtractor {
    private static final int MAX_INPUT_CHARS = 2048;

    private static final Pattern OTP_KEYWORD_PATTERN = Pattern.compile(
            "验证码|校验码|动态码|安全码|确认码|认证码|短信码|随机码|登录码|交易码|动态密码|一次性密码|授权码|短信验证"
                    + "|verification\\s*code|verify\\s*code|security\\s*code|auth(?:entication)?\\s*code"
                    + "|login\\s*code|sign[\\s-]?in\\s*code|pass\\s*code|one[\\s-]?time\\s*(?:password|passcode|code)"
                    + "|confirmation\\s*code|access\\s*code|pin\\s*code|\\botp\\b|\\b2fa\\b"
                    + "|\\bcode\\s*(?:is|:|#)|(?:use|enter|your)\\s+code",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern DIGIT_CANDIDATE_PATTERN = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");

    private static final Pattern MONEY_PREFIX_PATTERN = Pattern.compile(
            "(?:[¥￥$€£]|(?:RMB|CNY|USD|HKD|HK\\$|EUR|GBP)\\s*|金额[为是：:\\s]*|消费[了：:\\s]*|支付[了：:\\s]*|扣款[：:\\s]*|转账[：:\\s]*|余额[为是：:\\s]*|存入[：:\\s]*|支出[：:\\s]*|实付[：:\\s]*|应付[：:\\s]*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MONEY_SUFFIX_PATTERN = Pattern.compile(
            "^(?:\\.[0-9]+|\\s*(?:元|块|角|分|万元|万|千|百|美元|港币|日元|欧元|%|RMB|CNY|USD|HKD))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DURATION_SUFFIX_PATTERN = Pattern.compile(
            "^\\s*(?:分钟|分内|小时|秒钟|秒内|秒|天内|天|日|月|mins?\\b|minutes?\\b|secs?\\b|seconds?\\b|hours?\\b|hrs?\\b|days?\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NON_OTP_LABEL_PREFIX_PATTERN = Pattern.compile(
            "(?:尾号[为是：:\\s]*|尾数[为是：:\\s]*|卡号[为是：:\\s]*|账号[为是：:\\s]*|账户[为是：:\\s]*|订单号[为是：:\\s]*|单号[为是：:\\s]*"
                    + "|运单号[为是：:\\s]*|编号[为是：:\\s]*|客服热线[：:\\s]*|客服电话[：:\\s]*|客服[：:\\s]*|热线[：:\\s]*|致电[：:\\s]*"
                    + "|拨打[：:\\s]*|电话[：:\\s]*|详询[：:\\s]*|咨询[：:\\s]*|联系[：:\\s]*|退订请?回复[：:\\s]*|回复[：:\\s]*"
                    + "|order\\s*(?:#|no\\.?|number)?\\s*[:：]?\\s*|card\\s+ending\\s*(?:in\\s*)?"
                    + "|ending\\s+in\\s*|call\\s+|tel\\s*[:：]?\\s*|phone\\s*[:：]?\\s*|copyright\\s*|©\\s*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DIRECT_AFTER_OTP_CONNECTOR = Pattern.compile(
            "^[\\s为是:：=#\\-\\[【(（'\"`]*$");

    private static final Pattern DIRECT_BEFORE_OTP_CONNECTOR = Pattern.compile(
            "^[\\s（(\\[【,，为是]*(?:您的|你的|is\\s+your|as\\s+your)?[\\s]*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HOTLINE_DIGITS_PATTERN = Pattern.compile(
            "^(?:95\\d{3}|96\\d{3}|10086|10010|10000|12306|12315|12345|400\\d{1,5}|800\\d{1,5})$");

    private OtpCodeExtractor() {}

    /**
     * Extracts a 4-8 digit verification code from notification {@code title} and {@code text},
     * or returns {@code ""} if no high-confidence OTP is present.
     */
    public static String extract(String title, String text) {
        String t = (title != null) ? title.trim() : "";
        String b = (text != null) ? text.trim() : "";
        if (t.isEmpty() && b.isEmpty()) return "";
        if (t.isEmpty()) return extract(b);
        if (b.isEmpty()) return extract(t);
        return extract(t + "\n" + b);
    }

    public static String extract(String rawMessage) {
        if (rawMessage == null) return "";
        String msg = rawMessage.trim();
        if (msg.isEmpty()) return "";
        if (msg.length() > MAX_INPUT_CHARS) {
            msg = msg.substring(0, MAX_INPUT_CHARS);
        }

        List<int[]> keywordSpans = new ArrayList<>();
        Matcher kwMatcher = OTP_KEYWORD_PATTERN.matcher(msg);
        while (kwMatcher.find()) {
            keywordSpans.add(new int[]{kwMatcher.start(), kwMatcher.end()});
        }
        if (keywordSpans.isEmpty()) {
            return "";
        }

        String bestCode = "";
        int bestScore = Integer.MIN_VALUE;

        Matcher candMatcher = DIGIT_CANDIDATE_PATTERN.matcher(msg);
        while (candMatcher.find()) {
            int start = candMatcher.start();
            int end = candMatcher.end();
            String digits = candMatcher.group();

            if (isRejectedCandidate(msg, start, end, digits, keywordSpans)) {
                continue;
            }
            int score = scoreCandidate(msg, start, end, digits, keywordSpans);
            if (score > bestScore) {
                bestScore = score;
                bestCode = digits;
            }
        }
        return (bestScore > 0) ? bestCode : "";
    }

    private static boolean isRejectedCandidate(
            String msg, int start, int end, String digits, List<int[]> keywordSpans) {
        char prevChar = (start > 0) ? msg.charAt(start - 1) : '\0';
        char nextChar = (end < msg.length()) ? msg.charAt(end) : '\0';

        // Reject decimal fractions (e.g. ".1234" or "1234.56")
        if (prevChar == '.') {
            return true;
        }
        // Reject international phone prefix (+86...)
        if (prevChar == '+') {
            return true;
        }
        // Reject hyphen-chained tokens (phone numbers 400-888-1234, dates 2026-05-12)
        // while still allowing single-letter prefix codes like Google's "G-492018".
        if (nextChar == '-' && end + 1 < msg.length() && Character.isDigit(msg.charAt(end + 1))) {
            return true;
        }
        if (prevChar == '-') {
            boolean isGoogleStylePrefix = (start >= 2)
                    && Character.isLetter(msg.charAt(start - 2))
                    && (start == 2 || !Character.isLetterOrDigit(msg.charAt(start - 3)));
            if (!isGoogleStylePrefix) {
                return true;
            }
        }
        // Reject slash-chained dates (e.g. 2026/05/12)
        if ((prevChar == '/' && start >= 2 && Character.isDigit(msg.charAt(start - 2)))
                || (nextChar == '/' && end + 1 < msg.length() && Character.isDigit(msg.charAt(end + 1)))) {
            return true;
        }

        String beforeWindow = msg.substring(Math.max(0, start - 20), start);
        String afterWindow = msg.substring(end, Math.min(msg.length(), end + 16));

        // Reject money amounts (prefix currency or suffix currency/decimal)
        if (MONEY_PREFIX_PATTERN.matcher(beforeWindow).find()
                || MONEY_SUFFIX_PATTERN.matcher(afterWindow).find()) {
            return true;
        }

        // Reject durations / dates (e.g. "5分钟", "2026年")
        if (DURATION_SUFFIX_PATTERN.matcher(afterWindow).find()) {
            return true;
        }
        if (isYearInDateContext(digits, afterWindow, beforeWindow)) {
            return true;
        }

        // Reject explicitly labeled non-OTP identifiers (card tail, order id, hotline, etc.)
        if (NON_OTP_LABEL_PREFIX_PATTERN.matcher(beforeWindow).find()) {
            return true;
        }

        // Guard common Chinese service hotlines (95555, 10086, 400xxx) unless directly attached after OTP keyword
        if (HOTLINE_DIGITS_PATTERN.matcher(digits).matches()
                && !isDirectlyFollowingKeyword(msg, start, keywordSpans)) {
            return true;
        }

        // Guard bare 4-digit year (1990..2099) unless directly attached to an OTP keyword
        if (digits.length() == 4) {
            int val = Integer.parseInt(digits);
            if (val >= 1990 && val <= 2099 && !isDirectlyAdjacentToKeyword(msg, start, end, keywordSpans)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isYearInDateContext(String digits, String afterWindow, String beforeWindow) {
        if (digits.length() != 4) return false;
        int val = Integer.parseInt(digits);
        if (val < 1900 || val > 2099) return false;
        String trimmedAfter = afterWindow.trim();
        if (trimmedAfter.startsWith("年") || trimmedAfter.startsWith("-") || trimmedAfter.startsWith("/")) {
            return true;
        }
        String lowerBefore = beforeWindow.toLowerCase(Locale.ROOT);
        return lowerBefore.endsWith("©") || lowerBefore.contains("copyright");
    }

    private static boolean isDirectlyFollowingKeyword(String msg, int start, List<int[]> keywordSpans) {
        for (int[] kw : keywordSpans) {
            if (start >= kw[1] && start - kw[1] <= 8) {
                String between = msg.substring(kw[1], start);
                if (DIRECT_AFTER_OTP_CONNECTOR.matcher(between).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDirectlyAdjacentToKeyword(String msg, int start, int end, List<int[]> keywordSpans) {
        if (isDirectlyFollowingKeyword(msg, start, keywordSpans)) {
            return true;
        }
        for (int[] kw : keywordSpans) {
            if (end <= kw[0] && kw[0] - end <= 12) {
                String between = msg.substring(end, kw[0]);
                if (DIRECT_BEFORE_OTP_CONNECTOR.matcher(between).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int scoreCandidate(
            String msg, int start, int end, String digits, List<int[]> keywordSpans) {
        int lenBonus;
        if (digits.length() == 6) {
            lenBonus = 100;
        } else if (digits.length() == 4) {
            lenBonus = 60;
        } else {
            lenBonus = 40;
        }

        int best = Integer.MIN_VALUE;
        for (int[] kw : keywordSpans) {
            int kwStart = kw[0];
            int kwEnd = kw[1];
            if (start >= kwEnd) {
                int dist = start - kwEnd;
                if (dist > 64) continue;
                String between = msg.substring(kwEnd, start);
                boolean crossesSentence = hasSentenceBoundary(between);
                if (dist <= 24 && !crossesSentence) {
                    int bonus = DIRECT_AFTER_OTP_CONNECTOR.matcher(between).matches() ? 180 : 0;
                    best = Math.max(best, 1000 - dist * 10 + bonus + lenBonus);
                } else if (!crossesSentence) {
                    best = Math.max(best, 420 - dist * 4 + lenBonus);
                } else if (dist <= 40) {
                    // Handles title="短信验证码" + "\n" + text="请输入 741852 完成验证"
                    best = Math.max(best, 320 - dist * 3 + lenBonus);
                }
            } else if (end <= kwStart) {
                int dist = kwStart - end;
                if (dist > 48) continue;
                String between = msg.substring(end, kwStart);
                boolean crossesSentence = hasSentenceBoundary(between);
                if (dist <= 20 && !crossesSentence) {
                    int bonus = DIRECT_BEFORE_OTP_CONNECTOR.matcher(between).matches() ? 120 : 0;
                    best = Math.max(best, 850 - dist * 10 + bonus + lenBonus);
                } else if (!crossesSentence) {
                    best = Math.max(best, 340 - dist * 4 + lenBonus);
                }
            }
        }
        return best;
    }

    private static boolean hasSentenceBoundary(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '!' || c == '！' || c == '?' || c == '？' || c == ';' || c == '；' || c == '\n') {
                return true;
            }
        }
        return false;
    }
}
