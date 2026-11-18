package dev.androidagent.sample;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.security.SecureRandom;

/** Runs under the test APK's UID. The challenge is drawn as pixels and has no accessibility text. */
public final class AndroidUseFixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        render(intent);
    }

    private void render(Intent intent) {
        int secure = WindowManager.LayoutParams.FLAG_SECURE;
        if (intent.getBooleanExtra("protected", false)) {
            getWindow().addFlags(secure);
        } else {
            getWindow().clearFlags(secure);
        }
        if (intent.getBooleanExtra("tapFixture", false)) {
            renderTapFixture();
            return;
        }
        if (intent.getBooleanExtra("semanticFixture", false)) {
            renderSemanticFixture();
            return;
        }

        SecureRandom random = new SecureRandom();
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int index = 0; index < 5; index++) {
            code.append(letters.charAt(random.nextInt(letters.length())));
        }
        String secret = code.toString();
        View challenge = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.rgb(245, 245, 245));
                paint.setColor(Color.BLACK);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
                paint.setTextSize(Math.min(getWidth() * 0.19f, getHeight() * 0.75f));
                float baseline = getHeight() / 2f - (paint.ascent() + paint.descent()) / 2f;
                canvas.drawText(secret, getWidth() / 2f, baseline, paint);
            }
        };
        challenge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        TextView heading = label("Android Use Test", 24);
        TextView status = label("Waiting for code", 20);
        EditText entry = new EditText(this);
        entry.setHint("Enter the code shown above");
        entry.setContentDescription("Code entry");
        entry.setTextColor(Color.BLACK);
        entry.setInputType(InputType.TYPE_CLASS_TEXT);
        entry.setSingleLine(true);
        entry.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                status.setText(secret.equalsIgnoreCase(value.toString().trim()) ? "Code accepted" : "Waiting for code");
            }
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(55), dp(20), dp(20));
        content.setBackgroundColor(Color.WHITE);
        content.setFocusableInTouchMode(true);
        content.addView(heading);
        content.addView(challenge, new LinearLayout.LayoutParams(-1, dp(125)));
        content.addView(entry);
        content.addView(status);
        content.requestFocus();
        setContentView(content);
    }

    private void renderTapFixture() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        content.setBackgroundColor(Color.WHITE);

        LinearLayout row = new LinearLayout(this);
        TextView title = label("Test list result", 20);
        row.addView(title);
        TextView status = label("Ready for tap", 20);
        row.setOnClickListener(ignored -> status.setText("Row tapped"));
        content.addView(row, new LinearLayout.LayoutParams(-1, dp(56)));
        content.addView(status);

        TextView canvasLabel = label("Coordinate target", 20);
        canvasLabel.setBackgroundColor(Color.YELLOW);
        content.addView(canvasLabel, new LinearLayout.LayoutParams(-1, dp(64)));
        // The target receives touch input without exposing ACTION_CLICK to accessibility.
        canvasLabel.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) status.setText("Coordinate tapped");
            return true;
        });

        TextView changeOutside = label("Change outside", 20);
        changeOutside.setOnClickListener(ignored -> status.setText("Outside changed"));
        content.addView(changeOutside, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView changeTarget = label("Change target", 20);
        changeTarget.setOnClickListener(ignored -> canvasLabel.setBackgroundColor(Color.GREEN));
        content.addView(changeTarget, new LinearLayout.LayoutParams(-1, dp(56)));
        setContentView(content);
    }

    private void renderSemanticFixture() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);
        content.setPadding(dp(16), dp(24), dp(16), dp(12));

        TextView readback = label("Range readback 40", 18);
        content.addView(readback);
        SeekBar range = new SeekBar(this);
        range.setContentDescription("Test range");
        range.setMax(100);
        range.setProgress(40);
        range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                readback.setText("Range readback " + value);
            }
        });
        content.addView(range);

        CheckBox check = new CheckBox(this);
        check.setText("Test checkbox");
        check.setTextColor(Color.BLACK);
        content.addView(check);
        TextView tab = label("Test tab", 18);
        tab.setOnClickListener(view -> view.setSelected(!view.isSelected()));
        content.addView(tab);

        ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("Test results");
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        for (int index = 1; index <= 24; index++) {
            items.addView(label("Result " + index, 20), new LinearLayout.LayoutParams(-1, dp(52)));
        }
        scroll.addView(items);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
