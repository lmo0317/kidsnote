package com.lmo0317.kidsnote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends Activity {
  private final ExecutorService io = Executors.newFixedThreadPool(4);
  private final ArrayList<Photo> photos = new ArrayList<>();
  private final HashMap<String, Bitmap> cache = new HashMap<>();
  private TextView statusText, countText;
  private ProgressBar progress;
  private GridView gallery;
  private Spinner yearSpinner;
  private Button downloadButton;
  private LinearLayout webPanel;
  private WebView webView;
  private String childId = "", enrollment = "";

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    statusText = findViewById(R.id.statusText);
    countText = findViewById(R.id.countText);
    progress = findViewById(R.id.progress);
    gallery = findViewById(R.id.gallery);
    yearSpinner = findViewById(R.id.yearSpinner);
    downloadButton = findViewById(R.id.downloadButton);
    webPanel = findViewById(R.id.webPanel);
    webView = findViewById(R.id.webView);

    ArrayList<String> years = new ArrayList<>();
    int current = Calendar.getInstance().get(Calendar.YEAR);
    for (int y = current; y >= 2022; y--) years.add(y + "년");
    yearSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, years));

    setupWebView();
    findViewById(R.id.loginButton).setOnClickListener(v -> openLogin());
    findViewById(R.id.closeWebButton).setOnClickListener(v -> webPanel.setVisibility(View.GONE));
    findViewById(R.id.loadButton).setOnClickListener(v -> loadYear());
    downloadButton.setOnClickListener(v -> saveAll());
    gallery.setAdapter(new PhotoAdapter());
  }

  private void setupWebView() {
    android.webkit.CookieManager.getInstance().setAcceptCookie(true);
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    webView.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        Matcher matcher = Pattern.compile("/children/(\\d+)/(reports|albums)").matcher(url);
        if (matcher.find()) {
          childId = matcher.group(1);
          String header = request.getRequestHeaders().get("X-Enrollment");
          if (header != null) enrollment = header;
          runOnUiThread(() -> statusText.setText("키즈노트 연결됨 · 자녀 " + childId));
        }
        return super.shouldInterceptRequest(view, request);
      }
      @Override public void onPageFinished(WebView view, String url) {
        if (!url.matches(".*\\/(?:[a-z]{2}\\/)?login(?:[/?].*)?$")) {
          if (!url.contains("/service/report")) view.loadUrl("https://www.kidsnote.com/service/report");
          else if (!childId.isEmpty()) webPanel.setVisibility(View.GONE);
        }
      }
    });
  }

  private void openLogin() {
    webPanel.setVisibility(View.VISIBLE);
    webView.loadUrl("https://www.kidsnote.com/login");
  }

  private String selectedYear() {
    return yearSpinner.getSelectedItem().toString().replace("년", "");
  }

  private void loadYear() {
    if (childId.isEmpty()) {
      Toast.makeText(this, "먼저 키즈노트에 로그인해 주세요.", Toast.LENGTH_SHORT).show();
      openLogin();
      return;
    }
    setLoading(true, selectedYear() + "년 사진을 불러오는 중");
    io.execute(() -> {
      try {
        LinkedHashMap<String, Photo> found = new LinkedHashMap<>();
        loadCollection("reports", selectedYear(), found);
        loadCollection("albums", selectedYear(), found);
        runOnUiThread(() -> {
          photos.clear(); photos.addAll(found.values()); cache.clear();
          ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
          countText.setText(photos.size() + "장");
          downloadButton.setEnabled(!photos.isEmpty());
          setLoading(false, selectedYear() + "년 사진 " + photos.size() + "장");
        });
      } catch (Exception error) {
        runOnUiThread(() -> setLoading(false, "불러오기 실패: " + error.getMessage()));
      }
    });
  }

  private void loadCollection(String type, String year, Map<String, Photo> found) throws Exception {
    String next = "https://www.kidsnote.com/api/v1_2/children/" + childId + "/" + type + "/?page_size=5000";
    for (int page = 0; next != null && page < 50; page++) {
      JSONObject payload = requestJson(next);
      JSONArray items = payload.optJSONArray("results");
      if (items == null) items = payload.optJSONArray(type);
      if (items == null) items = payload.optJSONArray("data");
      if (items != null) for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null) continue;
        String date = first(item, "date_written", "written_at", "created_at", "date");
        if (!date.startsWith(year)) continue;
        collectImages(item, found);
      }
      next = payload.isNull("next") ? null : payload.optString("next", null);
      if (next != null && next.isEmpty()) next = null;
    }
  }

  private JSONObject requestJson(String url) throws Exception {
    HttpURLConnection connection = connection(url);
    connection.setRequestProperty("Accept", "application/json");
    int code = connection.getResponseCode();
    if (code == 401 || code == 403) throw new IOException("로그인이 만료되었습니다.");
    if (code < 200 || code >= 300) throw new IOException("키즈노트 응답 " + code);
    try (InputStream in = connection.getInputStream()) {
      return new JSONObject(readText(in));
    }
  }

  private HttpURLConnection connection(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
    connection.setRequestProperty("Cookie", android.webkit.CookieManager.getInstance().getCookie("https://www.kidsnote.com"));
    connection.setRequestProperty("User-Agent", "KidsNoteGallery-Android/1.0");
    if (!enrollment.isEmpty()) connection.setRequestProperty("X-Enrollment", enrollment);
    return connection;
  }

  private void collectImages(Object value, Map<String, Photo> found) throws JSONException {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      Iterator<String> keys = object.keys();
      while (keys.hasNext()) collectImages(object.opt(keys.next()), found);
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) collectImages(array.opt(i), found);
    } else if (value instanceof String) {
      String url = (String) value;
      if (url.startsWith("http") && url.matches("(?i).*(jpg|jpeg|png|webp|gif|heic)(\\?.*)?$")) {
        String key = url.replaceAll("\\?.*$", "").replaceAll("(?i)/(thumb|small|medium)/", "/");
        found.put(key, new Photo(url));
      }
    }
  }

  private Bitmap loadBitmap(String url) throws Exception {
    synchronized (cache) { if (cache.containsKey(url)) return cache.get(url); }
    HttpURLConnection connection = connection(url);
    connection.setRequestProperty("Accept", "image/*");
    try (InputStream input = connection.getInputStream()) {
      Bitmap bitmap = BitmapFactory.decodeStream(input);
      synchronized (cache) { cache.put(url, bitmap); }
      return bitmap;
    }
  }

  private void saveAll() {
    if (photos.isEmpty()) return;
    progress.setMax(photos.size()); progress.setProgress(0); progress.setVisibility(View.VISIBLE);
    downloadButton.setEnabled(false);
    io.execute(() -> {
      int saved = 0;
      for (int i = 0; i < photos.size(); i++) {
        try {
          Bitmap bitmap = loadBitmap(photos.get(i).url);
          ContentValues values = new ContentValues();
          values.put(MediaStore.Images.Media.DISPLAY_NAME, "kidsnote_" + selectedYear() + "_" + String.format(Locale.US, "%04d", i + 1) + ".jpg");
          values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
          values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KidsNote/" + selectedYear());
          Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
          if (uri != null) try (OutputStream out = getContentResolver().openOutputStream(uri)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out); }
          saved++;
        } catch (Exception ignored) {}
        int done = i + 1;
        runOnUiThread(() -> progress.setProgress(done));
      }
      int finalSaved = saved;
      runOnUiThread(() -> {
        progress.setVisibility(View.GONE); downloadButton.setEnabled(true);
        Toast.makeText(this, "Pictures/KidsNote/" + selectedYear() + "에 " + finalSaved + "장 저장됨", Toast.LENGTH_LONG).show();
      });
    });
  }

  private void setLoading(boolean loading, String message) {
    statusText.setText(message);
    progress.setIndeterminate(loading);
    progress.setVisibility(loading ? View.VISIBLE : View.GONE);
  }

  private static String first(JSONObject object, String... keys) {
    for (String key : keys) { String value = object.optString(key, ""); if (!value.isEmpty()) return value; }
    return "";
  }
  private static String readText(InputStream input) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
    while ((read = input.read(buffer)) >= 0) out.write(buffer, 0, read);
    return out.toString("UTF-8");
  }

  private class PhotoAdapter extends BaseAdapter {
    public int getCount() { return photos.size(); }
    public Object getItem(int p) { return photos.get(p); }
    public long getItemId(int p) { return p; }
    public View getView(int position, View convert, android.view.ViewGroup parent) {
      ImageView image = convert instanceof ImageView ? (ImageView) convert : new ImageView(MainActivity.this);
      image.setLayoutParams(new AbsListView.LayoutParams(-1, dp(124)));
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.setBackgroundColor(Color.rgb(16, 27, 38));
      image.setImageDrawable(null);
      String url = photos.get(position).url;
      io.execute(() -> {
        try { Bitmap bitmap = loadBitmap(url); runOnUiThread(() -> { if (url.equals(image.getTag())) image.setImageBitmap(bitmap); }); }
        catch (Exception ignored) {}
      });
      image.setTag(url);
      return image;
    }
  }
  private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
  private static class Photo { final String url; Photo(String url) { this.url = url; } }

  @Override protected void onDestroy() { webView.destroy(); io.shutdownNow(); super.onDestroy(); }
}
