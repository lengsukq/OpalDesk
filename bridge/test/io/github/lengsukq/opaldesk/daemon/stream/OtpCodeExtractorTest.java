package io.github.lengsukq.opaldesk.daemon.stream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OtpCodeExtractorTest {

    @Test
    public void testChineseSmsVerificationCodes() {
        assertEquals("839204",
                OtpCodeExtractor.extract("短信", "【微信】您的登录验证码为 839204，5分钟内有效。"));
        assertEquals("6521",
                OtpCodeExtractor.extract("", "【阿里云】验证码6521，您正在登录，切勿泄露给他人。"));
        assertEquals("194827",
                OtpCodeExtractor.extract("", "【美团】194827（登录验证码，请完成验证），如非本人操作请忽略"));
        assertEquals("382910",
                OtpCodeExtractor.extract("", "动态密码: 382910，有效期3分钟"));
        assertEquals("774128",
                OtpCodeExtractor.extract("", "【顺丰速运】登录校验码 774128"));
        assertEquals("741852",
                OtpCodeExtractor.extract("短信验证码", "您正在登录，请输入 741852 完成验证"));
    }

    @Test
    public void testEnglishSmsVerificationCodes() {
        assertEquals("583921",
                OtpCodeExtractor.extract("Apple", "Your Apple ID verification code is: 583921. Don't share it."));
        assertEquals("492018",
                OtpCodeExtractor.extract("Google", "Your Google verification code is G-492018"));
        assertEquals("738492",
                OtpCodeExtractor.extract("Microsoft", "Use 738492 as your Microsoft account security code."));
        assertEquals("48291012",
                OtpCodeExtractor.extract("GitHub", "48291012 is your authentication code."));
        assertEquals("9042",
                OtpCodeExtractor.extract("Auth", "Your OTP is 9042. Valid for 10 minutes."));
        assertEquals("615293",
                OtpCodeExtractor.extract("", "Sign-in code: 615293"));
    }

    @Test
    public void testBankSmsWithMoneyYearCardTailAndHotlineSelectsTrueOtp() {
        String sms = "【招商银行】您尾号8848的信用卡于2026年05月消费¥1280.00，本次交易验证码：482910，5分钟内有效，详询95555";
        assertEquals("482910", OtpCodeExtractor.extract("招商银行", sms));

        String sms2 = "【工商银行】尾号6612账户支出5000元，动态码 918273，如有疑问请致电95588";
        assertEquals("918273", OtpCodeExtractor.extract("", sms2));
    }

    @Test
    public void testFalsePositivesAreRejected() {
        // No OTP keyword at all
        assertEquals("",
                OtpCodeExtractor.extract("快递通知", "您的订单 20260512 已发货，消费 ¥1999.00，联系 13800138000"));
        // Contains OTP keyword, but only phone numbers / hotlines
        assertEquals("",
                OtpCodeExtractor.extract("客服", "客服热线 400-888-1234，验证码发送失败请致电 95555 或 13812345678"));
        // Contains OTP keyword, but only year / date
        assertEquals("",
                OtpCodeExtractor.extract("通知", "2026年验证码服务升级通知，将于2026-05-12生效"));
        // Contains OTP keyword, but only monetary amount
        assertEquals("",
                OtpCodeExtractor.extract("账单", "消费¥1280元，如需开通交易验证码请联系客服"));
        // Contains OTP keyword, but only card tail
        assertEquals("",
                OtpCodeExtractor.extract("银行", "您尾号8848的卡已关闭短信验证码功能"));
        // Null / blank safety
        assertEquals("", OtpCodeExtractor.extract(null, null));
        assertEquals("", OtpCodeExtractor.extract("", "   "));
    }
}
