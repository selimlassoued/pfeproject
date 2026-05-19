<#assign name = (user.firstName)!"">
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>Verify your HireAI email</title>
</head>
<body style="margin:0;padding:0;background-color:#0b1026;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">

  <table width="100%" cellpadding="0" cellspacing="0" role="presentation"
         style="background:#0b1026;padding:48px 16px;">
    <tr><td align="center">
      <table width="560" cellpadding="0" cellspacing="0" role="presentation"
             style="max-width:560px;width:100%;">

        <#-- Logo -->
        <tr>
          <td align="center" style="padding-bottom:28px;">
            <table cellpadding="0" cellspacing="0" role="presentation">
              <tr>
                <td style="background:rgba(255,255,255,0.06);border:1px solid rgba(121,164,233,0.22);border-radius:14px;padding:11px 26px;">
                  <span style="font-size:20px;font-weight:700;color:#fffce5;letter-spacing:-0.4px;">
                    Hire<span style="color:#79a4e9;">AI</span>
                  </span>
                </td>
              </tr>
            </table>
          </td>
        </tr>

        <#-- Card -->
        <tr>
          <td style="background:linear-gradient(180deg,rgba(255,255,255,0.08),rgba(255,255,255,0.05));border:1px solid rgba(121,164,233,0.24);border-radius:28px;overflow:hidden;">
            <table width="100%" cellpadding="0" cellspacing="0" role="presentation">
              <tr>
                <td style="height:3px;background:linear-gradient(90deg,#1e40bc 0%,#79a4e9 100%);"></td>
              </tr>
            </table>
            <table width="100%" cellpadding="0" cellspacing="0" role="presentation"
                   style="padding:36px 40px 32px;">
              <tr>
                <td style="padding-bottom:6px;">
                  <p style="margin:0;font-size:22px;font-weight:700;color:#fffce5;line-height:1.3;letter-spacing:-0.02em;">
                    Verify your email
                  </p>
                </td>
              </tr>
              <tr>
                <td style="padding-bottom:24px;">
                  <table width="36" cellpadding="0" cellspacing="0" role="presentation">
                    <tr>
                      <td style="height:3px;background:linear-gradient(90deg,#1e40bc,#79a4e9);border-radius:2px;"></td>
                    </tr>
                  </table>
                </td>
              </tr>
              <tr>
                <td style="padding-bottom:16px;">
                  <p style="margin:0;font-size:16px;font-weight:600;color:#fffce5;">
                    Hello<#if name?has_content>, ${name}</#if>
                  </p>
                </td>
              </tr>
              <tr>
                <td style="padding-bottom:30px;">
                  <p style="margin:0;font-size:15px;color:rgba(248,250,252,0.85);line-height:1.75;">
                    An account was created with this email address on
                    <strong style="color:#fffce5;">HireAI</strong>. Confirm it&rsquo;s really you
                    by verifying your email &mdash; it only takes a click.
                  </p>
                </td>
              </tr>
              <tr>
                <td style="padding-bottom:26px;">
                  <table cellpadding="0" cellspacing="0" role="presentation">
                    <tr>
                      <td style="border-radius:999px;background:linear-gradient(135deg,#1e40bc,#79a4e9);box-shadow:0 10px 30px rgba(0,0,0,0.35);">
                        <a href="${link}"
                           style="display:inline-block;padding:14px 34px;font-size:14px;font-weight:600;color:#ffffff;text-decoration:none;letter-spacing:0.1px;">
                          Verify my email &rarr;
                        </a>
                      </td>
                    </tr>
                  </table>
                </td>
              </tr>
              <tr>
                <td style="padding-bottom:30px;">
                  <p style="margin:0;font-size:13px;color:rgba(248,250,252,0.55);line-height:1.7;">
                    This link expires in
                    <strong style="color:#79a4e9;">${linkExpirationFormatter(linkExpiration)}</strong>.
                  </p>
                </td>
              </tr>
              <tr>
                <td style="border-top:1px solid rgba(121,164,233,0.22);padding-top:24px;">
                  <p style="margin:0;font-size:12px;color:rgba(248,250,252,0.35);line-height:1.7;">
                    If you didn&rsquo;t create a HireAI account, you can safely ignore this
                    email &mdash; no account will be activated without this confirmation.
                  </p>
                </td>
              </tr>
            </table>
          </td>
        </tr>

        <#-- Footer -->
        <tr>
          <td align="center" style="padding-top:24px;">
            <p style="margin:0;font-size:12px;color:rgba(248,250,252,0.25);">
              &copy; 2026 HireAI &nbsp;&bull;&nbsp; All rights reserved
            </p>
          </td>
        </tr>

      </table>
    </td></tr>
  </table>
</body>
</html>
