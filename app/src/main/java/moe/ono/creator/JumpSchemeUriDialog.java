package moe.ono.creator;

import static moe.ono.util.analytics.ActionReporter.reportVisitor;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
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
import java.util.ArrayList;
import java.util.List;

import moe.ono.R;
import moe.ono.ui.CommonContextWrapper;
import moe.ono.util.AppRuntimeHelper;

@SuppressLint("ResourceType")
public class JumpSchemeUriDialog extends BottomPopupView {
    private static BasePopupView popupView;
    private EditText input;
    private TextView result;
    private String launchUri = "";

    public JumpSchemeUriDialog(@NonNull Context context) { super(context); }

    public static void createView(Context context) {
        Context fixed = CommonContextWrapper.createAppCompatContext(context);
        XPopup.Builder builder = new XPopup.Builder(fixed).moveUpToKeyboard(true).isDestroyOnDismiss(true);
        builder.maxHeight((int) (XPopupUtils.getScreenHeight(context) * .82f));
        builder.popupHeight((int) (XPopupUtils.getScreenHeight(context) * .78f));
        reportVisitor(AppRuntimeHelper.getAccount(), "CreateView-JumpSchemeUriDialog");
        popupView = builder.asCustom(new JumpSchemeUriDialog(fixed));
        popupView.show();
    }

    @Override protected void onCreate() {
        super.onCreate();
        input = findViewById(R.id.scheme_uri);
        result = findViewById(R.id.scheme_result);
        Button analyze = findViewById(R.id.btn_analyze);
        Button jump = findViewById(R.id.btn_jump);
        Button copy = findViewById(R.id.btn_copy);
        input.setVisibility(VISIBLE);
        input.clearFocus();

        analyze.setOnClickListener(v -> analyzeInput());
        copy.setOnClickListener(v -> {
            String text = launchUri.isEmpty() ? result.getText().toString() : launchUri;
            if (TextUtils.isEmpty(text)) return;
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Scheme / URL", text));
            Toast.makeText(getContext(), "已复制", Toast.LENGTH_SHORT).show();
        });
        jump.setOnClickListener(v -> confirmAndOpen());
    }

    private void analyzeInput() {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) {
            input.setError("请输入 Scheme 或 URL");
            return;
        }
        Uri uri;
        try { uri = Uri.parse(raw); }
        catch (Exception e) {
            result.setText("链接格式无法解析：" + e.getMessage());
            return;
        }
        String scheme = uri.getScheme() == null ? "(无)" : uri.getScheme();
        StringBuilder detail = new StringBuilder();
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

        String lower = scheme.toLowerCase();
        if ("http".equals(lower) || "https".equals(lower)) {
            launchUri = raw;
            detail.append("\n转换提示：这是普通网页 URL。网页链接没有通用的 QQ Scheme，");
            detail.append("可通过下方跳板交给系统/QQ 已注册的应用处理。");
        } else if ("mqqapi".equals(lower) || "mqq".equals(lower)) {
            launchUri = raw;
            detail.append("\n识别为 QQ Scheme，可直接通过跳板尝试打开。");
        } else {
            launchUri = raw;
            detail.append("\n自定义 Scheme：将先检查系统中是否存在可处理它的应用。");
        }
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
                if (decoded.matches("(?s).*(https?://|mqq(api)?://).*")) current = decoded;
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
        Uri uri = Uri.parse(target);
        String scheme = uri.getScheme();
        if (scheme == null || scheme.trim().isEmpty()) {
            input.setError("链接必须包含协议，例如 https:// 或 mqqapi://");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        List<ResolveInfo> handlers = getContext().getPackageManager().queryIntentActivities(intent, 0);
        if (handlers == null || handlers.isEmpty()) {
            new AlertDialog.Builder(getContext())
                    .setTitle("没有可用的跳转目标")
                    .setMessage("系统中没有应用声明能够处理该 Scheme。请检查链接或安装对应应用。")
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
                        if (handlers.size() > 1) {
                            getContext().startActivity(Intent.createChooser(intent, "选择打开方式"));
                        } else {
                            getContext().startActivity(intent);
                        }
                        dismiss();
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }
}
