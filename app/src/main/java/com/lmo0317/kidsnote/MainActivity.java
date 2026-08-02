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
import android.util.LruCache;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class MainActivity extends Activity {
  private static final int STORAGE_PERMISSION_REQUEST = 1001;
  private static final long DISK_CACHE_LIMIT = 192L * 1024 * 1024;
  private static final int MEMORY_CACHE_LIMIT = (int) Math.max(16L * 1024 * 1024,
      Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6));
  private final ExecutorService networkIo = Executors.newFixedThreadPool(2);
  private final ThreadPoolExecutor thumbnailIo = new ThreadPoolExecutor(
      3, 3, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(192), new ThreadPoolExecutor.DiscardOldestPolicy());
  private final ArrayList<Photo> photos = new ArrayList<>();
  private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(MEMORY_CACHE_LIMIT) {
    @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
  };
  private TextView countText, galleryTitle, loginStatusBadge, loginStatusDetail;
  private ProgressBar progress;
  private GridView gallery;
  private Spinner yearSpinner;
  private Button downloadButton;
  private LinearLayout webPanel, emptyState;
  private WebView webView;
  private volatile String childId = "", enrollment = "";
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private boolean sessionChecking = false, pendingLoad = false;
  private volatile int loadGeneration = 0;
  private final Runnable sessionTimeout = () -> {
    if (!childId.isEmpty()) return;
    sessionChecking = false;
    if (pendingLoad) {
      pendingLoad = false;
      setLoading(false, "로그인이 필요합니다");
      Toast.makeText(this, "먼저 로그인 버튼으로 키즈노트를 연결해 주세요.", Toast.LENGTH_LONG).show();
    }
    showDisconnected();
  };

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
    restoreSessionSilently();
    findViewById(R.id.loginButton).setOnClickListener(v -> openLogin());
    findViewById(R.id.closeWebButton).setOnClickListener(v -> webPanel.setVisibility(View.GONE));
    findViewById(R.id.loadButton).setOnClickListener(v -> loadYear());
    downloadButton.setOnClickListener(v -> confirmBackup());
    gallery.setAdapter(new PhotoAdapter());
    networkIo.execute(this::trimThumbnailDiskCache);
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
          runOnUiThread(() -> {
            mainHandler.removeCallbacks(sessionTimeout);
            sessionChecking = false;
            showConnected();
            webPanel.setVisibility(View.GONE);
            if (pendingLoad) {
              pendingLoad = false;
              setLoading(false, "로그인 확인 완료");
              loadYear();
            }
          });
        }
        return super.shouldInterceptRequest(view, request);
      }
      @Override public void onPageFinished(WebView view, String url) {
        if (url.matches(".*\\/(?:[a-z]{2}\\/)?login(?:[/?].*)?$")) {
          if (webPanel.getVisibility() != View.VISIBLE) {
            mainHandler.removeCallbacks(sessionTimeout);
            sessionChecking = false;
            showDisconnected();
          }
        } else {
          if (!url.contains("/service/report")) view.loadUrl("https://www.kidsnote.com/service/report");
          else if (!childId.isEmpty()) webPanel.setVisibility(View.GONE);
        }
      }
    });
  }

  private void openLogin() {
    mainHandler.removeCallbacks(sessionTimeout);
    sessionChecking = false;
    pendingLoad = false;
    loginStatusDetail.setText("로그인 화면에서 키즈노트 계정을 연결해 주세요");
    webPanel.setVisibility(View.VISIBLE);
    webView.loadUrl("https://www.kidsnote.com/login");
  }

  private void restoreSessionSilently() {
    sessionChecking = true;
    loginStatusDetail.setText("저장된 로그인 상태를 확인하는 중입니다");
    webPanel.setVisibility(View.GONE);
    webView.loadUrl("https://www.kidsnote.com/service/report");
    mainHandler.removeCallbacks(sessionTimeout);
    mainHandler.postDelayed(sessionTimeout, 8000);
  }

  private String selectedYear() {
    return yearSpinner.getSelectedItem().toString().replace("년", "");
  }

  private void loadYear() {
    if (childId.isEmpty()) {
      if (sessionChecking) {
        pendingLoad = true;
        setLoading(true, "로그인 상태를 확인하는 중");
      } else {
        Toast.makeText(this, "먼저 로그인 버튼으로 키즈노트를 연결해 주세요.", Toast.LENGTH_LONG).show();
      }
      return;
    }
    final String year = selectedYear();
    final int generation = ++loadGeneration;
    thumbnailIo.getQueue().clear();
    photos.clear();
    ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
    galleryTitle.setText(year + "년 사진");
    emptyState.setVisibility(View.VISIBLE);
    downloadButton.setEnabled(false);
    setLoading(true, year + "년 사진 목록을 불러오는 중");
    networkIo.execute(() -> {
      try {
        LinkedHashMap<String, Photo> found = new LinkedHashMap<>();
        loadCollection("reports", year, found);
        loadCollection("albums", year, found);
        runOnUiThread(() -> {
          if (generation != loadGeneration) return;
          photos.clear(); photos.addAll(found.values());
          photos.sort((left, right) -> right.date.compareTo(left.date));
          ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
          galleryTitle.setText(year + "년 사진");
          countText.setText(photos.isEmpty() ? "가져온 사진이 없습니다" : photos.size() + "장의 사진 · 눌러서 크게 보기");
          emptyState.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
          downloadButton.setEnabled(!photos.isEmpty());
          setLoading(false, photos.isEmpty() ? "사진이 없습니다" : photos.size() + "장의 사진을 가져왔습니다");
        });
      } catch (Exception error) {
        runOnUiThread(() -> { if (generation == loadGeneration) setLoading(false, "불러오기 실패: " + error.getMessage()); });
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
        String date = type.equals("albums")
            ? first(item, "created")
            : first(item, "date_written");
        if (!year.equals(yearOf(date))) continue;
        collectAttachedImages(item, date, found);
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

  private static String yearOf(String date) {
    Matcher matcher = Pattern.compile("(?:^|\\D)(20\\d{2})(?:\\D|$)").matcher(date == null ? "" : date);
    return matcher.find() ? matcher.group(1) : "";
  }

  private void collectAttachedImages(JSONObject item, String date, Map<String, Photo> found) throws JSONException {
    String[] arrayKeys = {"attached_images", "images", "photos", "image_files", "attachments"};
    for (String key : arrayKeys) {
      JSONArray images = item.optJSONArray(key);
      if (images == null) continue;
      for (int i = 0; i < images.length(); i++) addBestImage(images.opt(i), date, found);
    }
    String[] singleKeys = {"attached_image", "image", "photo"};
    for (String key : singleKeys) if (item.has(key)) addBestImage(item.opt(key), date, found);
  }

  private void addBestImage(Object value, String date, Map<String, Photo> found) {
    String url = "";
    if (value instanceof String) url = (String) value;
    else if (value instanceof JSONObject) {
      JSONObject image = (JSONObject) value;
      url = first(image, "original", "large", "url", "file", "image", "thumbnail");
    }
    if (!url.startsWith("http") || !url.matches("(?i).*(jpg|jpeg|png|webp|gif|heic)(\\?.*)?$")) return;
    String key = url.replaceAll("\\?.*$", "").replaceAll("(?i)/(thumb|thumbnail|small|medium|large|original)/", "/");
    found.putIfAbsent(key, new Photo(url, date));
  }

  private byte[] downloadBytes(String url) throws Exception {
    HttpURLConnection connection = connection(url);
    connection.setRequestProperty("Accept", "image/*");
    try (InputStream input = connection.getInputStream()) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[16384]; int read;
      while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
      return output.toByteArray();
    }
  }

  private Bitmap decodeImage(byte[] data, int maxDimension) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    if (maxDimension > 0) {
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(data, 0, data.length, options);
      options.inSampleSize = 1;
      while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= maxDimension) options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      options.inPreferredConfig = Bitmap.Config.RGB_565;
    }
    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (bitmap == null) throw new IOException("이미지를 읽을 수 없습니다.");
    return bitmap;
  }

  private Bitmap loadThumbnail(String url, int generation) throws Exception {
    Bitmap cached = thumbnailCache.get(url);
    if (cached != null) return cached;
    File diskFile = thumbnailFile(url);
    if (diskFile.isFile()) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inPreferredConfig = Bitmap.Config.RGB_565;
      Bitmap diskBitmap = BitmapFactory.decodeFile(diskFile.getAbsolutePath(), options);
      if (diskBitmap != null) {
        diskFile.setLastModified(System.currentTimeMillis());
        if (generation != loadGeneration) { diskBitmap.recycle(); return null; }
        thumbnailCache.put(url, diskBitmap);
        return diskBitmap;
      }
      diskFile.delete();
    }
    Bitmap bitmap = decodeImage(downloadBytes(url), 320);
    if (generation != loadGeneration) {
      bitmap.recycle();
      return null;
    }
    thumbnailCache.put(url, bitmap);
    File directory = diskFile.getParentFile();
    if ((directory.isDirectory() || directory.mkdirs()) && !diskFile.exists()) {
      try (OutputStream output = new BufferedOutputStream(new FileOutputStream(diskFile))) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output);
      } catch (IOException ignored) {}
    }
    return bitmap;
  }

  private File thumbnailFile(String url) throws NoSuchAlgorithmException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder name = new StringBuilder(64);
    for (byte value : digest) name.append(String.format(Locale.US, "%02x", value & 0xff));
    return new File(new File(getCacheDir(), "thumbnails"), name + ".jpg");
  }

  private void trimThumbnailDiskCache() {
    File directory = new File(getCacheDir(), "thumbnails");
    File[] files = directory.listFiles();
    if (files == null) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    long total = 0;
    for (File file : files) total += file.length();
    for (File file : files) {
      if (total <= DISK_CACHE_LIMIT) break;
      long length = file.length();
      if (file.delete()) total -= length;
    }
  }

  private Bitmap loadOriginal(String url, int maxDimension) throws Exception {
    return decodeImage(downloadBytes(url), maxDimension);
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
    networkIo.execute(() -> {
      int saved = 0;
      for (int i = 0; i < photos.size(); i++) {
        try {
          Bitmap bitmap = loadOriginal(photos.get(i).url, 0);
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
          bitmap.recycle();
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
        .setTitle(selectedYear() + "년 원본 사진을 저장할까요?")
        .setMessage("현재 화면의 이미지는 임시 썸네일입니다.\n\n" + photos.size() + "장의 원본을 이 기기의\nPictures/KidsNote/" + selectedYear() + " 폴더에 저장합니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton("원본 저장", (dialog, which) -> saveAll())
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

  private void showDisconnected() {
    loginStatusBadge.setText("●  로그인 필요");
    loginStatusBadge.setTextColor(Color.rgb(179, 68, 60));
    loginStatusBadge.setBackgroundResource(R.drawable.bg_status_off);
    loginStatusDetail.setText("사진 목록을 불러오려면 로그인해 주세요");
    ((Button) findViewById(R.id.loginButton)).setText("로그인");
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
    networkIo.execute(() -> {
      try {
        Bitmap bitmap = loadOriginal(photo.url, 2048);
        runOnUiThread(() -> {
          loading.setVisibility(View.GONE); full.setImageBitmap(bitmap);
          dialog.setOnDismissListener(ignored -> { full.setImageDrawable(null); if (!bitmap.isRecycled()) bitmap.recycle(); });
        });
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
      image.animate().cancel();
      image.setAlpha(1f);
      image.setBackgroundColor(Color.rgb(226, 234, 232));
      image.setImageDrawable(null);
      String url = photos.get(position).url;
      image.setOnClickListener(v -> showPhoto(photos.get(position)));
      Bitmap cached = thumbnailCache.get(url);
      if (cached != null) image.setImageBitmap(cached);
      else {
        int generation = loadGeneration;
        thumbnailIo.execute(() -> {
          try {
            Bitmap bitmap = loadThumbnail(url, generation);
            if (bitmap != null) runOnUiThread(() -> {
              if (generation == loadGeneration && url.equals(image.getTag())) {
                image.setAlpha(.35f);
                image.setImageBitmap(bitmap);
                image.animate().alpha(1f).setDuration(140).start();
              }
            });
          }
          catch (Exception ignored) {}
        });
      }
      image.setTag(url);
      return image;
    }
  }
  private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
  private static class Photo {
    final String url, date;
    Photo(String url, String date) { this.url = url; this.date = date == null ? "" : date; }
  }

  @Override protected void onDestroy() {
    mainHandler.removeCallbacks(sessionTimeout);
    webView.destroy();
    networkIo.shutdownNow();
    thumbnailIo.shutdownNow();
    super.onDestroy();
  }
}
