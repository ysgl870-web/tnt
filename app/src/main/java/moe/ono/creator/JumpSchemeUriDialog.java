package moe.ono.creator;

import static moe.ono.util.analytics.ActionReporter.reportVisitor;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.core.BottomPopupView;
import com.lxj.xpopup.util.XPopupUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import moe.ono.R;
import moe.ono.ui.CommonContextWrapper;
import moe.ono.util.AppRuntimeHelper;

@SuppressLint("ResourceType")
public class JumpSchemeUriDialog extends BottomPopupView {
    private static BasePopupView popupView;
    private EditText input;
    private TextView result;
    private TextView converted;
    private String launchUri = "";

    public JumpSchemeUriDialog(@NonNull Context context) { super(context); }

    public static void createView(Context context) {
        Context fixed = CommonContextWrapper.createAppCompatContext(context);
        XPopup.Builder builder = new XPopup.Builder(fixed).moveUpToKeyboard(true).isDestroyOnDismiss(true);
        builder.maxHeight((int) (XPopupUtils.getScreenHeight(context) * .86f));
        builder.popupHeight((int) (XPopupUtils.getScreenHeight(context) * .82f));
        reportVisitor(AppRuntimeHelper.getAccount(), "CreateView-JumpSchemeUriDialog");
        popupView = builder.asCustom(new JumpSchemeUriDialog(fixed));
        popupView.show();
    }

    @Override protected void onCreate() {
        super.onCreate();
        input = findViewById(R.id.scheme_uri);
        result = findViewById(R.id.scheme_result);
        converted = findViewById(R.id.scheme_converted);
        Button analyze = findViewById(R.id.btn_analyze);
        Button jump = findViewById(R.id.btn_jump);
        Button copy = findViewById(R.id.btn_copy);
        input.setVisibility(VISIBLE);
        input.clearFocus();

        analyze.setOnClickListener(v -> analyzeInput());
        copy.setOnClickListener(v -> {
            String text = converted.getText().toString();
            if (TextUtils.isEmpty(text)) text = result.getText().toString();
            if (TextUtils.isEmpty(text)) return;
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Scheme / URL", text));
            Toast.makeText(getContext(), "已复制转换结果", Toast.LENGTH_SHORT).show();
        });
        jump.setOnClickListener(v -> confirmAndOpen());
    }

    private void analyzeInput() {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) {
            input.setError("请输入 Scheme 或 URL");
            return;
        }
        launchUri = raw;
        StringBuilder detail = new StringBuilder();
        String convertedValue = raw;
        try {
            if (raw.startsWith("intent://") || raw.startsWith("intent:")) {
                Intent parsed = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
                Uri data = parsed.getData();
                if (data != null) {
                    convertedValue = data.toString();
                    launchUri = convertedValue;
                    detail.append("已识别 Android Intent Scheme\n");
                    detail.append("还原 URL：").append(convertedValue).append('\n');
                    detail.append("目标包名：").append(parsed.getPackage() == null ? "(未指定)" : parsed.getPackage()).append('\n');
                } else {
                    detail.append("这是 Intent Scheme，但没有可还原的数据 URL。\n");
                }
            } else {
                Uri uri = Uri.parse(raw);
                String scheme = uri.getScheme() == null ? "(无)" : uri.getScheme();
                detail.append("解析结果\n");
                detail.append("协议：").append(scheme).append('\n');
                detail.append("主机/目标：").append(uri.getAuthority() == null ? "(无)" : uri.getAuthority()).append('\n');
                detail.append("路径：").append(uri.getPath() == null ? "(无)" : uri.getPath()).append('\n');
                detail.append("\n参数与解码内容：\n");
                boolean found = false;
                for (String key : uri.getQueryParameterNames()) {
                    found = true;
                    String value = uri.getQueryParameter(key);
                    detail.append("• ").append(key).append(" = ").append(value).append('\n');
                    String decoded = decodeNested(value);
                    if (!decoded.equals(value)) detail.append("  ↳ 解码：").append(decoded).append('\n');
                }
                if (!found) detail.append("(没有查询参数)\n");

                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    Intent bridge = new Intent(Intent.ACTION_VIEW, uri);
                    convertedValue = bridge.toUri(Intent.URI_INTENT_SCHEME);
                    detail.append("\n已生成 Android Intent Scheme。它是系统级跳转封装，不等同于 QQ 专属 mqqapi Scheme。");
                } else if ("mqqapi".equalsIgnoreCase(scheme) || "mqq".equalsIgnoreCase(scheme)) {
                    detail.append("\n识别为 QQ Scheme，可通过下方跳板尝试打开。");
                } else {
                    detail.append("\n识别为自定义 Scheme；请先确认来源可信，再使用跳板打开。");
                }
            }
        } catch (Exception e) {
            detail.append("\n解析失败：").append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            convertedValue = "";
            launchUri = "";
        }
        converted.setText(convertedValue);
        result.setText(detail.toString());
    }

    private String decodeNested(String value) {
        String current = value == null ? "" : value;
        for (int i = 0; i < 3; i++) {
            String next = current;
            try { next = URLDecoder.decode(current, "UTF-8"); } catch (Exception ignored) {}
            if (!next.equals(current)) { current = next; continue; }
            try {
                String candidate = current.replace('-', '+').replace('_', '/');
                int mod = candidate.length() % 4;
                if (mod != 0) candidate += "====".substring(0, 4 - mod);
                byte[] bytes = android.util.Base64.decode(candidate, android.util.Base64.DEFAULT);
                String decoded = new String(bytes, StandardCharsets.UTF_8);
                if (decoded.matches("(?s).*(https?://|mqq(api)?://|intent:).*")) current = decoded;
                else break;
            } catch (Exception ignored) { break; }
        }
        return current;
    }

    private void confirmAndOpen() {
        String target = input.getText().toString().trim();
        if (target.isEmpty()) {
            input.setError("请先输入链接");
            return;
        }
        try {
            Intent intent;
            if (target.startsWith("intent://") || target.startsWith("intent:")) {
                intent = Intent.parseUri(target, Intent.URI_INTENT_SCHEME);
                intent.setAction(Intent.ACTION_VIEW);
            } else {
                Uri uri = Uri.parse(target);
                if (TextUtils.isEmpty(uri.getScheme())) {
                    input.setError("链接必须包含协议，例如 https:// 或 mqqapi://");
                    return;
                }
                intent = new Intent(Intent.ACTION_VIEW, uri);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            List<ResolveInfo> handlers = getContext().getPackageManager().queryIntentActivities(intent, 0);
            if (handlers == null || handlers.isEmpty()) {
                new AlertDialog.Builder(getContext())
                        .setTitle("没有可用的跳转目标")
                        .setMessage("系统中没有可处理该链接的应用。若这是网页链接，请检查浏览器；若是 QQ Scheme，请确认 QQ 已安装且协议有效。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            String appName = handlers.get(0).loadLabel(getContext().getPackageManager()).toString();
            String packageName = handlers.get(0).activityInfo.packageName;
            new AlertDialog.Builder(getContext())
                    .setTitle("确认通过跳板打开")
                    .setMessage("目标应用：" + appName + "\n包名：" + packageName
                            + "\n\n链接可能会触发目标应用中的页面或操作，请确认来源可信后继续。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("打开", (dialog, which) -> {
                        try {
                            getContext().startActivity(handlers.size() > 1
                                    ? Intent.createChooser(intent, "选择打开方式") : intent);
                            dismiss();
                        } catch (Exception e) {
                            Toast.makeText(getContext(), "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "链接解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
