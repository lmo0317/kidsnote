package com.lmo0317.kidsnote;

import android.app.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.MediaScannerConnection;
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
  private static final int STORAGE_PERMISSION_REQUEST = 1001;
  private final ExecutorService io = Executors.newFixedThreadPool(4);
  private final ArrayList<Photo> photos = new ArrayList<>();
  private final HashMap<String, Bitmap> cache = new HashMap<>();
  private TextView countText, galleryTitle, loginStatusBadge, loginStatusDetail;
  private ProgressBar progress;
  private GridView gallery;
  private Spinner yearSpinner;
  private Button downloadButton;
  private LinearLayout webPanel, emptyState;
  private WebView webView;
  private String childId = "", enrollment = "";

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_main);
    countText = findViewById(R.id.countText);
    galleryTitle = findViewById(R.id.galleryTitle);
    loginStatusBadge = findViewById(R.id.loginStatusBadge);
    loginStatusDetail = findViewById(R.id.loginStatusDetail);
    progress = findViewById(R.id.progress);
    gallery = findViewById(R.id.gallery);
    yearSpinner = findViewById(R.id.yearSpinner);
    downloadButton = findViewById(R.id.downloadButton);
    webPanel = findViewById(R.id.webPanel);
    emptyState = findViewById(R.id.emptyState);
    webView = findViewById(R.id.webView);

    ArrayList<String> years = new ArrayList<>();
    int current = Calendar.getInstance().get(Calendar.YEAR);
    for (int y = current; y >= 2022; y--) years.add(y + "년");
    yearSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, years));

    setupWebView();
    findViewById(R.id.loginButton).setOnClickListener(v -> openLogin());
    findViewById(R.id.closeWebButton).setOnClickListener(v -> webPanel.setVisibility(View.GONE));
    findViewById(R.id.loadButton).setOnClickListener(v -> loadYear());
    downloadButton.setOnClickListener(v -> confirmBackup());
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
          runOnUiThread(() -> showConnected());
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
    loginStatusDetail.setText("로그인 화면에서 키즈노트 계정을 연결해 주세요");
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
          galleryTitle.setText(selectedYear() + "년 사진");
          countText.setText(photos.isEmpty() ? "가져온 사진이 없습니다" : photos.size() + "장의 사진 · 눌러서 크게 보기");
          emptyState.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
          downloadButton.setEnabled(!photos.isEmpty());
          setLoading(false, photos.isEmpty() ? "사진이 없습니다" : photos.size() + "장의 사진을 가져왔습니다");
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
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
      return;
    }
    progress.setMax(photos.size()); progress.setProgress(0); progress.setVisibility(View.VISIBLE);
    downloadButton.setEnabled(false);
    io.execute(() -> {
      int saved = 0;
      for (int i = 0; i < photos.size(); i++) {
        try {
          Bitmap bitmap = loadBitmap(photos.get(i).url);
          String fileName = "kidsnote_" + selectedYear() + "_" + String.format(Locale.US, "%04d", i + 1) + ".jpg";
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KidsNote/" + selectedYear());
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("사진 저장 위치를 만들 수 없습니다.");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
              if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("사진 저장 실패");
            }
          } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KidsNote/" + selectedYear());
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("사진 폴더를 만들 수 없습니다.");
            File target = new File(directory, fileName);
            try (OutputStream out = new FileOutputStream(target)) {
              if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("사진 저장 실패");
            }
            MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
          }
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

  private void confirmBackup() {
    if (photos.isEmpty()) return;
    new AlertDialog.Builder(this)
        .setTitle(selectedYear() + "년 사진을 백업할까요?")
        .setMessage(photos.size() + "장의 사진을 이 기기의\nPictures/KidsNote/" + selectedYear() + " 폴더에 저장합니다.\n\n갤러리에서 언제든 다시 볼 수 있습니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton("백업 시작", (dialog, which) -> saveAll())
        .show();
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != STORAGE_PERMISSION_REQUEST) return;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) saveAll();
    else Toast.makeText(this, "사진을 저장하려면 저장소 권한이 필요합니다.", Toast.LENGTH_LONG).show();
  }

  private void setLoading(boolean loading, String message) {
    countText.setText(message);
    progress.setIndeterminate(loading);
    progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    findViewById(R.id.loadButton).setEnabled(!loading);
  }

  private void showConnected() {
    loginStatusBadge.setText("●  로그인됨");
    loginStatusBadge.setTextColor(Color.rgb(18, 119, 88));
    loginStatusBadge.setBackgroundResource(R.drawable.bg_status_on);
    loginStatusDetail.setText("키즈노트 계정이 연결되었습니다 · 자녀 " + childId);
    ((Button) findViewById(R.id.loginButton)).setText("다시 로그인");
  }

  private void showPhoto(Photo photo) {
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.BLACK);
    ImageView full = new ImageView(this);
    full.setScaleType(ImageView.ScaleType.FIT_CENTER);
    frame.addView(full, new FrameLayout.LayoutParams(-1, -1));
    ProgressBar loading = new ProgressBar(this);
    FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
    frame.addView(loading, loadingParams);
    Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    dialog.setContentView(frame);
    frame.setOnClickListener(v -> dialog.dismiss());
    dialog.show();
    io.execute(() -> {
      try {
        Bitmap bitmap = loadBitmap(photo.url);
        runOnUiThread(() -> { loading.setVisibility(View.GONE); full.setImageBitmap(bitmap); });
      } catch (Exception error) {
        runOnUiThread(() -> { dialog.dismiss(); Toast.makeText(this, "사진을 열 수 없습니다.", Toast.LENGTH_SHORT).show(); });
      }
    });
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
      image.setOnClickListener(v -> showPhoto(photos.get(position)));
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
