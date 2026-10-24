package com.wifibilling.service;

import org.springframework.stereotype.Component;

/**
 * Branded transactional email bodies (AirPairy / Thewton). Plain, table-based
 * HTML so it renders in every mail client; no external assets.
 */
@Component
public class EmailTemplates {

    private String shell(String title, String body, String accent) {
        return """
                <!doctype html>
                <html><body style="margin:0;padding:0;background:#F5F7FA;font-family:Arial,Helvetica,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#F5F7FA;padding:32px 12px;">
                <tr><td align="center">
                  <table role="presentation" width="520" cellpadding="0" cellspacing="0"
                         style="max-width:520px;width:100%%;background:#ffffff;border:1px solid #E2E8F0;border-radius:14px;overflow:hidden;">
                    <tr><td style="background:#122B49;padding:20px 28px;">
                      <span style="color:#ffffff;font-size:18px;font-weight:bold;letter-spacing:-0.3px;">AirPairy</span>
                    </td></tr>
                    <tr><td style="padding:28px;">
                      <h1 style="margin:0 0 14px;font-size:17px;color:#0F172A;">%s</h1>
                      %s
                    </td></tr>
                    <tr><td style="padding:16px 28px;border-top:1px solid #F1F5F9;">
                      <p style="margin:0;font-size:11px;color:#94A3B8;">
                        AirPairy is a product by Thewton · %s
                      </p>
                    </td></tr>
                  </table>
                </td></tr>
                </table></body></html>
                """.formatted(title, body, accent);
    }

    private String codeBlock(String code, int minutes) {
        return """
                <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                  Use this verification code — it expires in <b>%d minutes</b>:
                </p>
                <div style="text-align:center;margin:22px 0;">
                  <span style="display:inline-block;background:#F1F5F9;border:1px solid #E2E8F0;border-radius:12px;
                               padding:14px 30px;font-size:30px;font-weight:bold;letter-spacing:8px;color:#0F172A;
                               font-family:Menlo,Consolas,monospace;">%s</span>
                </div>
                """.formatted(minutes, code);
    }

    private String warning(String text) {
        return """
                <p style="margin:18px 0 0;font-size:12px;color:#94A3B8;line-height:1.6;">
                  If you didn't request this, you can ignore this email — nothing will happen without the code. %s
                </p>
                """.formatted(text);
    }

    public String otpEmail(String businessName, String code, int minutes, String context) {
        String body = """
                <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                  Hi %s,
                </p>
                """.formatted(escape(businessName))
                + codeBlock(code, minutes)
                + "<p style=\"margin:0;font-size:14px;color:#334155;line-height:1.6;\">" + escape(context) + "</p>"
                + warning("");
        return shell("Your verification code", body, "");
    }

    public String passwordResetEmail(String businessName, String code, int minutes) {
        String body = """
                <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                  Hi %s,
                </p>
                <p style="margin:0 0 4px;font-size:14px;color:#334155;line-height:1.6;">
                  We received a request to reset the password for your AirPairy account.
                </p>
                """.formatted(escape(businessName))
                + codeBlock(code, minutes)
                + warning("If you did not request a password reset, your password stays unchanged.");
        return shell("Reset your password", body, "");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
