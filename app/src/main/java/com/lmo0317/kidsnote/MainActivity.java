package com.lmo0317.kidsnote;

import android.app.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
  private static final int FOLDER_BY_DAY = 0;
  private static final int FOLDER_BY_MONTH = 1;
  private static final long DISK_CACHE_LIMIT = 192L * 1024 * 1024;
  private static final int MEMORY_CACHE_LIMIT = (int) Math.max(16L * 1024 * 1024,
      Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6));
  private final ExecutorService networkIo = Executors.newFixedThreadPool(2);
  private final ThreadPoolExecutor thumbnailIo = new ThreadPoolExecutor(
      3, 3, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(192), new ThreadPoolExecutor.DiscardOldestPolicy());
  private final ArrayList<Photo> photos = new ArrayList<>();
  private final ArrayList<Notice> notices = new ArrayList<>();
  private final ArrayList<Photo> savedPhotos = new ArrayList<>();
  private final LruCache<String, Bitmap> thumbnailCache = new LruCache<String, Bitmap>(MEMORY_CACHE_LIMIT) {
    @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
  };
  private TextView countText, galleryTitle, loginStatusBadge, loginStatusDetail, previewTab, savedTab, savedCountText;
  private ProgressBar progress;
  private GridView gallery, savedGallery;
  private ScaleGestureDetector galleryScaleDetector;
  private int galleryColumns = 3;
  private float galleryScale = 1f;
  private Spinner yearSpinner, savedYearSpinner;
  private Button downloadButton;
  private LinearLayout webPanel, emptyState, savedEmptyState, previewTabContent, savedTabContent;
  private WebView webView;
  private volatile String childId = "", enrollment = "", loginId = "";
  private Photo pendingSingleSave;
  private Button pendingSingleSaveButton;
  private int pendingFolderMode = FOLDER_BY_DAY;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private boolean sessionChecking = false, pendingLoad = false, resettingLogin = false,
      loginVerificationStarted = false;
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
    applySystemBarInsets();
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
    previewTab = findViewById(R.id.previewTab);
    savedTab = findViewById(R.id.savedTab);
    savedCountText = findViewById(R.id.savedCountText);
    savedGallery = findViewById(R.id.savedGallery);
    savedYearSpinner = findViewById(R.id.savedYearSpinner);
    savedEmptyState = findViewById(R.id.savedEmptyState);
    previewTabContent = findViewById(R.id.previewTabContent);
    savedTabContent = findViewById(R.id.savedTabContent);
    webView = findViewById(R.id.webView);
    loginId = getPreferences(MODE_PRIVATE).getString("login_id", "");
    pendingFolderMode = getPreferences(MODE_PRIVATE).getInt("backup_folder_mode", FOLDER_BY_DAY);
    galleryColumns = Math.max(2, Math.min(5, getPreferences(MODE_PRIVATE).getInt("gallery_columns", 3)));

    ArrayList<String> years = new ArrayList<>();
    int current = Calendar.getInstance().get(Calendar.YEAR);
    for (int y = current; y >= 2022; y--) years.add(y + "년");
    yearSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, years));
    ArrayList<String> savedYears = new ArrayList<>();
    savedYears.add("전체 연도");
    savedYears.addAll(years);
    savedYearSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, savedYears));
    savedYearSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (savedTabContent != null && savedTabContent.getVisibility() == View.VISIBLE) loadSavedPhotos();
      }
      @Override public void onNothingSelected(AdapterView<?> parent) {}
    });

    setupWebView();
    restoreSessionSilently();
    findViewById(R.id.loginButton).setOnClickListener(v -> openLogin());
    findViewById(R.id.appDisclosure).setOnClickListener(v -> showAppDisclosure());
    findViewById(R.id.closeWebButton).setOnClickListener(v -> webPanel.setVisibility(View.GONE));
    findViewById(R.id.loadButton).setOnClickListener(v -> loadYear());
    downloadButton.setOnClickListener(v -> chooseBackupFoldering());
    gallery.setAdapter(new PhotoAdapter(photos, gallery));
    savedGallery.setAdapter(new PhotoAdapter(savedPhotos, savedGallery));
    gallery.setNumColumns(galleryColumns);
    savedGallery.setNumColumns(galleryColumns);
    gallery.setOnItemClickListener((parent, view, position, id) -> {
      if (position >= 0 && position < photos.size()) showPhoto(position);
    });
    savedGallery.setOnItemClickListener((parent, view, position, id) -> {
      if (position >= 0 && position < savedPhotos.size()) showPhoto(savedPhotos, position, false);
    });
    previewTab.setOnClickListener(v -> showTab(false));
    savedTab.setOnClickListener(v -> showTab(true));
    setupGalleryPinch();
    networkIo.execute(this::trimThumbnailDiskCache);
  }

  private void showAppDisclosure() {
    new AlertDialog.Builder(this)
        .setTitle("비공식 사진 저장 도우미")
        .setMessage("이 앱은 키즈노트 사진을 사용자의 기기에 저장하도록 돕는 독립적인 비공식 앱입니다. " +
            "키즈노트 운영사와 제휴하거나 운영사가 제공하는 공식 앱이 아닙니다.\n\n" +
            "로그인 정보와 사진은 별도 개발자 서버로 전송하지 않으며, 키즈노트와 사용자 기기 사이에서만 처리합니다.")
        .setPositiveButton("개인정보처리방침", (dialog, which) -> startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://github.com/lmo0317/kidsnote/blob/main/PRIVACY_POLICY.md"))))
        .setNegativeButton("닫기", null)
        .show();
  }

  private void applySystemBarInsets() {
    View root = findViewById(R.id.rootView);
    applyInsets(root);
  }

  private void setupGalleryPinch() {
    galleryScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
        galleryScale = 1f;
        return true;
      }

      @Override public boolean onScale(ScaleGestureDetector detector) {
        galleryScale *= detector.getScaleFactor();
        if (galleryScale >= 1.18f && galleryColumns > 2) {
          setGalleryColumns(galleryColumns - 1);
          galleryScale = 1f;
        } else if (galleryScale <= .84f && galleryColumns < 5) {
          setGalleryColumns(galleryColumns + 1);
          galleryScale = 1f;
        }
        return true;
      }
    });
    View.OnTouchListener pinchListener = new View.OnTouchListener() {
      private boolean pinching;
      @Override public boolean onTouch(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
          pinching = true;
          view.getParent().requestDisallowInterceptTouchEvent(true);
        }
        galleryScaleDetector.onTouchEvent(event);
        boolean consume = pinching;
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
          pinching = false;
          view.getParent().requestDisallowInterceptTouchEvent(false);
        }
        return consume;
      }
    };
    gallery.setOnTouchListener(pinchListener);
    savedGallery.setOnTouchListener(pinchListener);
  }

  private void setGalleryColumns(int columns) {
    columns = Math.max(2, Math.min(5, columns));
    if (columns == galleryColumns) return;
    int firstVisible = gallery.getFirstVisiblePosition();
    galleryColumns = columns;
    gallery.setNumColumns(columns);
    savedGallery.setNumColumns(columns);
    ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
    gallery.post(() -> gallery.setSelection(Math.max(0, firstVisible)));
    getPreferences(MODE_PRIVATE).edit().putInt("gallery_columns", columns).apply();
  }

  private int galleryCellSize() {
    return galleryCellSize(gallery);
  }

  private int galleryCellSize(GridView target) {
    int width = target.getWidth();
    if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
    return Math.max(dp(68), (width - dp(8) - dp(3) * (galleryColumns - 1)) / galleryColumns);
  }

  private void showTab(boolean saved) {
    findViewById(R.id.appHeader).setVisibility(View.VISIBLE);
    previewTabContent.setVisibility(saved ? View.GONE : View.VISIBLE);
    savedTabContent.setVisibility(saved ? View.VISIBLE : View.GONE);
    previewTab.setBackgroundResource(saved ? R.drawable.bg_tab_unselected : R.drawable.bg_tab_selected);
    savedTab.setBackgroundResource(saved ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
    previewTab.setTextColor(getColor(saved ? R.color.text_secondary : R.color.mint_dark));
    savedTab.setTextColor(getColor(saved ? R.color.mint_dark : R.color.text_secondary));
    if (saved) loadSavedPhotos();
  }

  private static void appendJsonText(Object value, StringBuilder out, int depth) {
    if (value == null || value == JSONObject.NULL || depth > 4) return;
    if (value instanceof String) out.append(' ').append(value);
    else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) appendJsonText(array.opt(i), out, depth + 1);
    } else if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      Iterator<String> keys = object.keys();
      while (keys.hasNext()) appendJsonText(object.opt(keys.next()), out, depth + 1);
    }
  }

  private void loadSavedPhotos() {
    String selected = savedYearSpinner.getSelectedItem() == null ? "전체 연도" : savedYearSpinner.getSelectedItem().toString();
    String year = selected.replace("년", "");
    boolean allYears = selected.equals("전체 연도");
    savedCountText.setText((allYears ? "전체" : year + "년") + " 사진을 확인하는 중");
    networkIo.execute(() -> {
      ArrayList<Photo> found = new ArrayList<>();
      Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
      String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
          MediaStore.Images.Media.DISPLAY_NAME};
      String selection;
      String[] arguments;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        arguments = new String[]{allYears ? "Pictures/KidsNote/%" : "Pictures/KidsNote/" + year + "%"};
      } else {
        selection = MediaStore.Images.Media.DATA + " LIKE ?";
        arguments = new String[]{allYears ? "%/Pictures/KidsNote/%" : "%/Pictures/KidsNote/" + year + "/%"};
      }
      try (Cursor cursor = getContentResolver().query(collection, projection, selection, arguments,
          MediaStore.Images.Media.DATE_ADDED + " DESC")) {
        if (cursor != null) {
          int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
          int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
          while (cursor.moveToNext()) {
            long id = cursor.getLong(idColumn);
            long seconds = cursor.getLong(dateColumn);
            Uri uri = Uri.withAppendedPath(collection, Long.toString(id));
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date(seconds * 1000L));
            found.add(new Photo(uri.toString(), date));
          }
        }
      } catch (Exception ignored) {}
      runOnUiThread(() -> {
        savedPhotos.clear();
        savedPhotos.addAll(found);
        ((BaseAdapter) savedGallery.getAdapter()).notifyDataSetChanged();
        savedCountText.setText(found.isEmpty() ? (allYears ? "저장된 사진이 없습니다" : year + "년 저장 사진이 없습니다")
            : (allYears ? "전체 " : year + "년 ") + found.size() + "장 · 눌러서 크게 보기");
        savedEmptyState.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
      });
    });
  }

  private void applyInsets(View target) {
    target.setOnApplyWindowInsetsListener((view, windowInsets) -> {
      int left, top, right, bottom;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.graphics.Insets bars = windowInsets.getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
      } else {
        left = windowInsets.getSystemWindowInsetLeft();
        top = windowInsets.getSystemWindowInsetTop();
        right = windowInsets.getSystemWindowInsetRight();
        bottom = windowInsets.getSystemWindowInsetBottom();
      }
      view.setPadding(left, top, right, bottom);
      return windowInsets;
    });
    target.requestApplyInsets();
  }

  private void setupWebView() {
    android.webkit.CookieManager.getInstance().setAcceptCookie(true);
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    webView.addJavascriptInterface(new LoginIdBridge(), "KidsNoteGallery");
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
        if (resettingLogin) return;
        installLoginNavigationCapture();
        if (url.matches(".*\\/(?:[a-z]{2}\\/)?login(?:[/?].*)?$")) {
          webView.setVisibility(View.VISIBLE);
          installLoginIdCapture();
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
    resettingLogin = true;
    loginVerificationStarted = false;
    childId = "";
    enrollment = "";
    loginId = "";
    getPreferences(MODE_PRIVATE).edit().remove("login_id").apply();
    webView.stopLoading();
    webView.clearHistory();
    webView.clearCache(true);
    webView.setVisibility(View.INVISIBLE);
    WebStorage.getInstance().deleteAllData();
    loginStatusDetail.setText("새 로그인 화면을 준비하는 중입니다");
    webPanel.setVisibility(View.VISIBLE);
    android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
    cookieManager.removeAllCookies(removed -> {
      cookieManager.flush();
      runOnUiThread(() -> {
        resettingLogin = false;
        loginStatusDetail.setText("로그인 화면에서 키즈노트 계정을 연결해 주세요");
        webView.loadUrl("https://www.kidsnote.com/login");
      });
    });
  }

  private void installLoginIdCapture() {
    webView.evaluateJavascript("(function(){" +
        "if(window.__kidsNoteIdCapture)return;window.__kidsNoteIdCapture=true;" +
        "function bind(){document.querySelectorAll('input[type=email],input[autocomplete=username],input[name*=email i],input[name*=username i],input[name*=login i]').forEach(function(el){" +
        "if(el.__kidsNoteBound)return;el.__kidsNoteBound=true;" +
        "function send(){if(el.value)window.KidsNoteGallery.captureLoginId(el.value);}" +
        "el.addEventListener('input',send);el.addEventListener('change',send);send();});}" +
        "bind();new MutationObserver(bind).observe(document.documentElement,{childList:true,subtree:true});" +
        "})();", null);
  }

  private void installLoginNavigationCapture() {
    webView.evaluateJavascript("(function(){" +
        "if(window.__kidsNoteNavigationCapture)return;window.__kidsNoteNavigationCapture=true;" +
        "var last=location.href;function report(){var now=location.href;if(now!==last){last=now;window.KidsNoteGallery.captureNavigation(now);}}" +
        "setInterval(report,250);window.addEventListener('popstate',report);window.addEventListener('hashchange',report);" +
        "})();", null);
  }

  private void verifyLoginAfterNavigation(String url) {
    if (resettingLogin || loginVerificationStarted || url == null ||
        url.matches(".*\\/(?:[a-z]{2}\\/)?login(?:[/?#].*)?$")) return;
    loginVerificationStarted = true;
    sessionChecking = true;
    loginStatusDetail.setText("로그인 완료를 확인하는 중입니다");
    webView.loadUrl("https://www.kidsnote.com/service/report");
  }

  private class LoginIdBridge {
    @JavascriptInterface public void captureLoginId(String value) {
      if (value == null) return;
      String cleaned = value.trim();
      if (cleaned.isEmpty() || cleaned.length() > 100) return;
      loginId = cleaned;
      getPreferences(MODE_PRIVATE).edit().putString("login_id", cleaned).apply();
    }

    @JavascriptInterface public void captureNavigation(String url) {
      runOnUiThread(() -> verifyLoginAfterNavigation(url));
    }
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
    notices.clear();
    ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
    galleryTitle.setText(year + "년 사진");
    emptyState.setVisibility(View.VISIBLE);
    downloadButton.setEnabled(false);
    setLoading(true, year + "년 사진 목록을 불러오는 중");
    networkIo.execute(() -> {
      try {
        LinkedHashMap<String, Photo> found = new LinkedHashMap<>();
        LinkedHashMap<String, Notice> foundNotices = new LinkedHashMap<>();
        loadCollection("reports", year, found, foundNotices);
        loadCollection("albums", year, found, foundNotices);
        runOnUiThread(() -> {
          if (generation != loadGeneration) return;
          photos.clear(); photos.addAll(found.values());
          notices.clear(); notices.addAll(foundNotices.values());
          photos.sort((left, right) -> right.date.compareTo(left.date));
          notices.sort((left, right) -> right.date.compareTo(left.date));
          ((BaseAdapter) gallery.getAdapter()).notifyDataSetChanged();
          galleryTitle.setText(year + "년 사진");
          countText.setText(photos.size() + "장의 사진 · 알림장 " + notices.size() + "개");
          emptyState.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
          downloadButton.setEnabled(!photos.isEmpty() || !notices.isEmpty());
          setLoading(false, "사진 " + photos.size() + "장 · 알림장 " + notices.size() + "개를 가져왔습니다");
        });
      } catch (Exception error) {
        runOnUiThread(() -> { if (generation == loadGeneration) setLoading(false, "불러오기 실패: " + error.getMessage()); });
      }
    });
  }

  private void loadCollection(String type, String year, Map<String, Photo> found,
      Map<String, Notice> foundNotices) throws Exception {
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
        if (type.equals("reports")) collectNotice(item, date, foundNotices);
      }
      next = payload.isNull("next") ? null : payload.optString("next", null);
      if (next != null && next.isEmpty()) next = null;
    }
  }

  private void collectNotice(JSONObject item, String date, Map<String, Notice> found) {
    String raw = collectNoticeText(item);
    String text = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        .replace('\u00a0', ' ').replaceAll("[ \\t]+\\n", "\\n").replaceAll("\\n{3,}", "\\n\\n").trim();
    if (text.isEmpty()) return;
    String id = first(item, "id", "report_id", "uuid");
    String title = first(item, "title", "subject", "name");
    String key = id.isEmpty() ? date + "|" + Integer.toHexString(text.hashCode()) : id;
    found.putIfAbsent(key, new Notice(date, title, text));
  }

  private static String collectNoticeText(JSONObject item) {
    StringBuilder out = new StringBuilder();
    String[] keys = {"content", "contents", "description", "body", "text", "memo", "notice", "message"};
    for (String key : keys) appendJsonText(item.opt(key), out, 0);
    if (out.toString().trim().isEmpty()) {
      String title = first(item, "title", "subject", "name");
      if (!title.isEmpty()) out.append(title);
    }
    return out.toString();
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
    String originalUrl = "";
    String thumbnailUrl = "";
    if (value instanceof String) originalUrl = (String) value;
    else if (value instanceof JSONObject) {
      JSONObject image = (JSONObject) value;
      originalUrl = first(image, "original", "large", "url", "file", "image", "medium", "small", "thumbnail");
      thumbnailUrl = first(image, "thumbnail", "thumb", "small", "medium", "large");
    }
    if (!isImageUrl(originalUrl)) return;
    if (!isImageUrl(thumbnailUrl)) thumbnailUrl = originalUrl;
    String key = originalUrl.replaceAll("\\?.*$", "").replaceAll("(?i)/(thumb|thumbnail|small|medium|large|original)/", "/");
    found.putIfAbsent(key, new Photo(originalUrl, thumbnailUrl, date));
  }

  private static boolean isImageUrl(String url) {
    return url != null && url.startsWith("http")
        && url.matches("(?i).*(jpg|jpeg|png|webp|gif|heic)(\\?.*)?$");
  }

  private byte[] downloadBytes(String url) throws Exception {
    InputStream source;
    if (url.startsWith("content://")) source = getContentResolver().openInputStream(Uri.parse(url));
    else {
      HttpURLConnection connection = connection(url);
      connection.setRequestProperty("Accept", "image/*");
      source = connection.getInputStream();
    }
    if (source == null) throw new IOException("사진을 열 수 없습니다.");
    try (InputStream input = source) {
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

  private void saveAll(int folderMode) {
    if (photos.isEmpty() && notices.isEmpty()) return;
    final String targetYear = selectedYear();
    pendingFolderMode = folderMode;
    pendingSingleSave = null;
    pendingSingleSaveButton = null;
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
      return;
    }
    progress.setMax(photos.size() + notices.size()); progress.setProgress(0); progress.setVisibility(View.VISIBLE);
    downloadButton.setEnabled(false);
    networkIo.execute(() -> {
      int saved = 0;
      int noticeSaved = 0;
      for (int i = 0; i < photos.size(); i++) {
        Bitmap bitmap = null;
        try {
          bitmap = loadOriginal(photos.get(i).url, 0);
          Photo photo = photos.get(i);
          String date = normalizedDate(photo.date, targetYear);
          String fileName = "kidsnote_" + date.replace("-", "") + "_" + Integer.toHexString(photo.url.hashCode()) + ".jpg";
          writeBitmapToGallery(bitmap, fileName, relativeFolder(date, folderMode));
          saved++;
        } catch (Exception ignored) {
        } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
        int done = i + 1;
        runOnUiThread(() -> progress.setProgress(done));
      }
      LinkedHashMap<String, ArrayList<Notice>> noticesByDate = new LinkedHashMap<>();
      for (Notice notice : notices) {
        String date = normalizedDate(notice.date, targetYear);
        noticesByDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(notice);
      }
      int noticeProgress = 0;
      for (Map.Entry<String, ArrayList<Notice>> entry : noticesByDate.entrySet()) {
        String date = entry.getKey();
        try {
          String fileName = folderMode == FOLDER_BY_DAY ? "알림장.txt" : "알림장_" + date + ".txt";
          writeTextFile(fileName, relativeFolder(date, folderMode), buildNoticeText(date, entry.getValue()));
          noticeSaved += entry.getValue().size();
        } catch (Exception ignored) {}
        noticeProgress += entry.getValue().size();
        int done = photos.size() + noticeProgress;
        runOnUiThread(() -> progress.setProgress(done));
      }
      int finalSaved = saved;
      int finalNoticeSaved = noticeSaved;
      String root = "Pictures/KidsNote/" + targetYear;
      runOnUiThread(() -> {
        progress.setVisibility(View.GONE); downloadButton.setEnabled(true);
        Toast.makeText(this, root + "에 사진 " + finalSaved + "장 · 알림장 " + finalNoticeSaved + "개 저장됨", Toast.LENGTH_LONG).show();
        if (savedTabContent.getVisibility() == View.VISIBLE) loadSavedPhotos();
      });
    });
  }

  private void writeBitmapToGallery(Bitmap bitmap, String fileName, String relativeFolder) throws IOException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
      values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
      values.put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder);
      Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
      if (uri == null) throw new IOException("사진 저장 위치를 만들 수 없습니다.");
      try (OutputStream out = getContentResolver().openOutputStream(uri)) {
        if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("사진 저장 실패");
      }
    } else {
      File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
          relativeFolder.replaceFirst("^Pictures/", ""));
      if (!directory.exists() && !directory.mkdirs()) throw new IOException("사진 폴더를 만들 수 없습니다.");
      File target = new File(directory, fileName);
      try (OutputStream out = new FileOutputStream(target)) {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("사진 저장 실패");
      }
      MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
    }
  }

  private void writeTextFile(String fileName, String relativeFolder, String text) throws IOException {
    byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
      Uri target = findStoredFile(collection, relativeFolder, fileName);
      if (target == null) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder);
        target = getContentResolver().insert(collection, values);
      }
      if (target == null) throw new IOException("알림장 저장 위치를 만들 수 없습니다.");
      try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
        if (out == null) throw new IOException("알림장 저장 실패");
        out.write(data);
      }
    } else {
      File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
          relativeFolder.replaceFirst("^Pictures/", ""));
      if (!directory.exists() && !directory.mkdirs()) throw new IOException("알림장 폴더를 만들 수 없습니다.");
      File target = new File(directory, fileName);
      try (OutputStream out = new FileOutputStream(target, false)) { out.write(data); }
      MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()}, new String[]{"text/plain"}, null);
    }
  }

  private Uri findStoredFile(Uri collection, String relativeFolder, String fileName) {
    String[] projection = {MediaStore.MediaColumns._ID};
    String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
        + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
    try (Cursor cursor = getContentResolver().query(collection, projection, selection,
        new String[]{relativeFolder.endsWith("/") ? relativeFolder : relativeFolder + "/", fileName}, null)) {
      if (cursor != null && cursor.moveToFirst())
        return Uri.withAppendedPath(collection, Long.toString(cursor.getLong(0)));
    } catch (Exception ignored) {}
    return null;
  }

  private static String normalizedDate(String value, String fallbackYear) {
    Matcher matcher = Pattern.compile("(20\\d{2})\\D(0?[1-9]|1[0-2])\\D(0?[1-9]|[12]\\d|3[01])").matcher(value == null ? "" : value);
    if (!matcher.find()) return fallbackYear + "-01-01";
    return String.format(Locale.US, "%04d-%02d-%02d", Integer.parseInt(matcher.group(1)),
        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
  }

  private static String relativeFolder(String date, int folderMode) {
    String[] parts = date.split("-");
    String path = "Pictures/KidsNote/" + parts[0] + "년/" + parts[1] + "월";
    return folderMode == FOLDER_BY_DAY ? path + "/" + parts[2] + "일" : path;
  }

  private static String buildNoticeText(String date, List<Notice> dayNotices) {
    StringBuilder text = new StringBuilder();
    text.append("키즈노트 알림장\n날짜: ").append(date).append("\n");
    for (int i = 0; i < dayNotices.size(); i++) {
      Notice notice = dayNotices.get(i);
      text.append("\n========================================\n");
      if (!notice.title.isEmpty()) text.append(notice.title).append("\n\n");
      text.append(notice.text.trim()).append("\n");
    }
    return text.toString();
  }

  private void saveSinglePhoto(Photo photo, Button button) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      pendingSingleSave = photo;
      pendingSingleSaveButton = button;
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
      return;
    }
    button.setEnabled(false);
    button.setText("원본 저장 중…");
    networkIo.execute(() -> {
      Bitmap bitmap = null;
      boolean success = false;
      try {
        bitmap = loadOriginal(photo.url, 0);
        String year = yearOf(photo.date).isEmpty() ? selectedYear() : yearOf(photo.date);
        String dateToken = photo.date.length() >= 10 ? photo.date.substring(0, 10).replaceAll("\\D", "") : year;
        String fileName = "kidsnote_" + dateToken + "_" + Integer.toHexString(photo.url.hashCode()) + ".jpg";
        String date = normalizedDate(photo.date, year);
        writeBitmapToGallery(bitmap, fileName, relativeFolder(date, pendingFolderMode));
        success = true;
      } catch (Exception ignored) {
      } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
      boolean saved = success;
      runOnUiThread(() -> {
        button.setEnabled(true);
        button.setText(saved ? "저장 완료 ✓" : "다시 저장");
        Toast.makeText(this, saved ? "사진 한 장을 갤러리에 저장했습니다." : "사진 저장에 실패했습니다.", Toast.LENGTH_LONG).show();
        if (saved && savedTabContent.getVisibility() == View.VISIBLE) loadSavedPhotos();
      });
    });
  }

  private void chooseBackupFoldering() {
    if (photos.isEmpty() && notices.isEmpty()) return;
    int savedMode = getPreferences(MODE_PRIVATE).getInt("backup_folder_mode", FOLDER_BY_DAY);
    final int[] selected = {savedMode};
    new AlertDialog.Builder(this)
        .setTitle("폴더 구성을 선택해 주세요")
        .setSingleChoiceItems(new String[]{"년 / 월 / 일", "년 / 월"}, savedMode,
            (dialog, which) -> selected[0] = which)
        .setNegativeButton("취소", null)
        .setPositiveButton("다음", (dialog, which) -> {
          getPreferences(MODE_PRIVATE).edit().putInt("backup_folder_mode", selected[0]).apply();
          confirmBackup(selected[0]);
        })
        .show();
  }

  private void confirmBackup(int folderMode) {
    String example = folderMode == FOLDER_BY_DAY
        ? "Pictures/KidsNote/" + selectedYear() + "/08/19"
        : "Pictures/KidsNote/" + selectedYear() + "/08";
    new AlertDialog.Builder(this)
        .setTitle(selectedYear() + "년 자료를 저장할까요?")
        .setMessage("사진 " + photos.size() + "장과 알림장 " + notices.size() + "개를 저장합니다.\n\n예시: " + example
            + (folderMode == FOLDER_BY_DAY ? "\n해당 날짜 폴더에 사진과 알림장.txt가 함께 들어갑니다." : "\n월 폴더에 사진과 날짜별 알림장 텍스트가 들어갑니다."))
        .setNegativeButton("취소", null)
        .setPositiveButton("저장", (dialog, which) -> saveAll(folderMode))
        .show();
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != STORAGE_PERMISSION_REQUEST) return;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      if (pendingSingleSave != null && pendingSingleSaveButton != null) {
        Photo photo = pendingSingleSave; Button button = pendingSingleSaveButton;
        pendingSingleSave = null; pendingSingleSaveButton = null;
        saveSinglePhoto(photo, button);
      } else saveAll(pendingFolderMode);
    } else Toast.makeText(this, "사진을 저장하려면 저장소 권한이 필요합니다.", Toast.LENGTH_LONG).show();
  }

  private void setLoading(boolean loading, String message) {
    countText.setText(message);
    progress.setIndeterminate(loading);
    progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    findViewById(R.id.loadButton).setEnabled(!loading);
  }

  private void showConnected() {
    loginStatusBadge.setText("●  연결됨");
    loginStatusBadge.setTextColor(Color.rgb(18, 119, 88));
    loginStatusBadge.setBackgroundResource(R.drawable.bg_status_on);
    loginStatusDetail.setText((loginId.isEmpty() ? "ID 확인 안 됨" : "ID " + loginId) + " · 자녀 " + childId);
    ((Button) findViewById(R.id.loginButton)).setText("계정 변경");
  }

  private void showDisconnected() {
    loginStatusBadge.setText("●  로그인 필요");
    loginStatusBadge.setTextColor(Color.rgb(179, 68, 60));
    loginStatusBadge.setBackgroundResource(R.drawable.bg_status_off);
    loginStatusDetail.setText("계정을 연결해 주세요");
    ((Button) findViewById(R.id.loginButton)).setText("로그인");
  }

  private void showPhoto(int startIndex) {
    showPhoto(photos, startIndex, true);
  }

  private void showPhoto(ArrayList<Photo> sourcePhotos, int startIndex, boolean allowSave) {
    if (startIndex < 0 || startIndex >= sourcePhotos.size()) return;
    Photo initialPhoto = sourcePhotos.get(startIndex);
    FrameLayout frame = new FrameLayout(this);
    frame.setBackgroundColor(Color.BLACK);
    ZoomImageView full = new ZoomImageView(this);
    frame.addView(full, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(18), dp(10), dp(8), dp(10));
    topBar.setBackgroundColor(Color.argb(190, 7, 17, 25));
    TextView dateText = new TextView(this);
    dateText.setText(displayDate(initialPhoto.date));
    dateText.setTextColor(Color.WHITE);
    dateText.setTextSize(18);
    dateText.setTypeface(null, android.graphics.Typeface.BOLD);
    topBar.addView(dateText, new LinearLayout.LayoutParams(0, dp(48), 1));
    Button close = new Button(this);
    close.setText("닫기");
    close.setTextColor(Color.WHITE);
    close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(20, 38, 48)));
    topBar.addView(close, new LinearLayout.LayoutParams(dp(76), dp(48)));
    FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP);
    frame.addView(topBar, topParams);

    Button save = new Button(this);
    save.setText("이 사진 원본 저장");
    save.setTextColor(Color.rgb(7, 61, 49));
    save.setTextSize(16);
    save.setTypeface(null, android.graphics.Typeface.BOLD);
    save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34, 201, 151)));
    FrameLayout.LayoutParams saveParams = new FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM);
    saveParams.setMargins(dp(22), 0, dp(22), dp(22));
    frame.addView(save, saveParams);
    save.setVisibility(allowSave ? View.VISIBLE : View.GONE);

    ProgressBar loading = new ProgressBar(this);
    FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
    frame.addView(loading, loadingParams);
    Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    dialog.setContentView(frame);
    close.setOnClickListener(v -> dialog.dismiss());
    dialog.show();
    applyInsets(frame);
    if (dialog.getWindow() != null) {
      dialog.getWindow().setStatusBarColor(Color.BLACK);
      dialog.getWindow().setNavigationBarColor(Color.BLACK);
      dialog.getWindow().getDecorView().setSystemUiVisibility(0);
    }
    int[] currentIndex = {startIndex};
    Bitmap[] displayedBitmap = {null};
    java.util.concurrent.atomic.AtomicInteger loadToken = new java.util.concurrent.atomic.AtomicInteger();
    java.util.function.IntConsumer displayPhoto = new java.util.function.IntConsumer() {
      @Override public void accept(int index) {
        if (index < 0 || index >= sourcePhotos.size() || index == currentIndex[0] && displayedBitmap[0] != null) return;
        int previousIndex = currentIndex[0];
        currentIndex[0] = index;
        Photo selected = sourcePhotos.get(index);
        int token = loadToken.incrementAndGet();
        dateText.setText(displayDate(selected.date));
        save.setEnabled(true);
        save.setText("이 사진 원본 저장");
        save.setOnClickListener(v -> saveSinglePhoto(selected, save));
        loading.setVisibility(View.VISIBLE);
        networkIo.execute(() -> {
          try {
            Bitmap bitmap = loadOriginal(selected.url, 2048);
            runOnUiThread(() -> {
              if (!dialog.isShowing() || token != loadToken.get()) {
                if (!bitmap.isRecycled()) bitmap.recycle();
                return;
              }
              Bitmap previous = displayedBitmap[0];
              displayedBitmap[0] = bitmap;
              full.setImageBitmap(bitmap);
              full.setTranslationX(index >= previousIndex ? dp(36) : -dp(36));
              full.setAlpha(.45f);
              full.animate().translationX(0).alpha(1f).setDuration(180).start();
              loading.setVisibility(View.GONE);
              if (previous != null && !previous.isRecycled()) previous.recycle();
            });
          } catch (Exception error) {
            runOnUiThread(() -> {
              if (token != loadToken.get()) return;
              currentIndex[0] = previousIndex;
              Photo previous = sourcePhotos.get(previousIndex);
              dateText.setText(displayDate(previous.date));
              save.setOnClickListener(v -> saveSinglePhoto(previous, save));
              loading.setVisibility(View.GONE);
              Toast.makeText(MainActivity.this, "사진을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            });
          }
        });
      }
    };
    full.setOnPhotoSwipeListener(direction -> displayPhoto.accept(currentIndex[0] + direction));
    dialog.setOnDismissListener(ignored -> {
      loadToken.incrementAndGet();
      full.setImageDrawable(null);
      if (displayedBitmap[0] != null && !displayedBitmap[0].isRecycled()) displayedBitmap[0].recycle();
    });
    displayPhoto.accept(startIndex);
  }

  private static String displayDate(String value) {
    Matcher matcher = Pattern.compile("(20\\d{2})-(\\d{2})-(\\d{2})").matcher(value == null ? "" : value);
    if (!matcher.find()) return "날짜 정보 없음";
    return Integer.parseInt(matcher.group(1)) + "년 " + Integer.parseInt(matcher.group(2)) + "월 " + Integer.parseInt(matcher.group(3)) + "일";
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
    private final ArrayList<Photo> items;
    private final GridView target;
    PhotoAdapter(ArrayList<Photo> items, GridView target) { this.items = items; this.target = target; }
    public int getCount() { return items.size(); }
    public Object getItem(int p) { return items.get(p); }
    public long getItemId(int p) { return p; }
    public View getView(int position, View convert, android.view.ViewGroup parent) {
      ImageView image = convert instanceof ImageView ? (ImageView) convert : new ImageView(MainActivity.this);
      image.setLayoutParams(new AbsListView.LayoutParams(-1, galleryCellSize(target)));
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.animate().cancel();
      image.setAlpha(1f);
      image.setBackgroundColor(Color.rgb(226, 234, 232));
      image.setImageDrawable(null);
      String url = items.get(position).thumbnailUrl;
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

  private static class ZoomImageView extends ImageView {
    interface OnPhotoSwipeListener { void onSwipe(int direction); }
    private final Matrix zoomMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float zoom = 1f;
    private OnPhotoSwipeListener photoSwipeListener;

    ZoomImageView(Context context) {
      super(context);
      setScaleType(ScaleType.MATRIX);
      scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override public boolean onScale(ScaleGestureDetector detector) {
          float requested = detector.getScaleFactor();
          float next = Math.max(1f, Math.min(5f, zoom * requested));
          float factor = next / zoom;
          zoom = next;
          zoomMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
          fixBounds();
          setImageMatrix(zoomMatrix);
          return true;
        }
      });
      gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
        @Override public boolean onDown(MotionEvent event) { return true; }
        @Override public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
          if (zoom <= 1f) return false;
          zoomMatrix.postTranslate(-distanceX, -distanceY);
          fixBounds();
          setImageMatrix(zoomMatrix);
          return true;
        }
        @Override public boolean onFling(MotionEvent first, MotionEvent last, float velocityX, float velocityY) {
          if (first == null || last == null || photoSwipeListener == null) return false;
          float distance = last.getX() - first.getX();
          float threshold = 56f * getResources().getDisplayMetrics().density;
          if (Math.abs(distance) < threshold || Math.abs(velocityX) < Math.abs(velocityY)) return false;
          if (zoom > 1f) {
            RectF bounds = mappedBounds();
            float tolerance = 3f * getResources().getDisplayMetrics().density;
            boolean atRequestedEdge = distance < 0
                ? bounds.right <= getWidth() + tolerance
                : bounds.left >= -tolerance;
            if (!atRequestedEdge) return false;
          }
          photoSwipeListener.onSwipe(distance < 0 ? 1 : -1);
          return true;
        }
        @Override public boolean onDoubleTap(MotionEvent event) {
          if (zoom > 1f) resetZoom();
          else {
            zoom = 2.5f;
            zoomMatrix.postScale(zoom, zoom, event.getX(), event.getY());
            fixBounds();
            setImageMatrix(zoomMatrix);
          }
          return true;
        }
      });
    }

    void setOnPhotoSwipeListener(OnPhotoSwipeListener listener) { photoSwipeListener = listener; }

    @Override public void setImageBitmap(Bitmap bitmap) {
      super.setImageBitmap(bitmap);
      post(this::resetZoom);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
      super.onSizeChanged(width, height, oldWidth, oldHeight);
      post(this::resetZoom);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
      scaleDetector.onTouchEvent(event);
      gestureDetector.onTouchEvent(event);
      return true;
    }

    private void resetZoom() {
      android.graphics.drawable.Drawable drawable = getDrawable();
      if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
      float drawableWidth = drawable.getIntrinsicWidth();
      float drawableHeight = drawable.getIntrinsicHeight();
      if (drawableWidth <= 0 || drawableHeight <= 0) return;
      float scale = Math.min(getWidth() / drawableWidth, getHeight() / drawableHeight);
      float dx = (getWidth() - drawableWidth * scale) / 2f;
      float dy = (getHeight() - drawableHeight * scale) / 2f;
      zoomMatrix.reset();
      zoomMatrix.postScale(scale, scale);
      zoomMatrix.postTranslate(dx, dy);
      zoom = 1f;
      setImageMatrix(zoomMatrix);
    }

    private void fixBounds() {
      android.graphics.drawable.Drawable drawable = getDrawable();
      if (drawable == null) return;
      RectF bounds = mappedBounds();
      float dx = 0f, dy = 0f;
      if (bounds.width() <= getWidth()) dx = (getWidth() - bounds.width()) / 2f - bounds.left;
      else if (bounds.left > 0) dx = -bounds.left;
      else if (bounds.right < getWidth()) dx = getWidth() - bounds.right;
      if (bounds.height() <= getHeight()) dy = (getHeight() - bounds.height()) / 2f - bounds.top;
      else if (bounds.top > 0) dy = -bounds.top;
      else if (bounds.bottom < getHeight()) dy = getHeight() - bounds.bottom;
      zoomMatrix.postTranslate(dx, dy);
    }

    private RectF mappedBounds() {
      android.graphics.drawable.Drawable drawable = getDrawable();
      if (drawable == null) return new RectF();
      RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
      zoomMatrix.mapRect(bounds);
      return bounds;
    }
  }
  private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
  private static class Photo {
    final String url, thumbnailUrl, date;
    Photo(String url, String date) { this(url, url, date); }
    Photo(String url, String thumbnailUrl, String date) {
      this.url = url;
      this.thumbnailUrl = thumbnailUrl;
      this.date = date == null ? "" : date;
    }
  }
  private static class Notice {
    final String date, title, text;
    Notice(String date, String title, String text) {
      this.date = date == null ? "" : date;
      this.title = title == null ? "" : title.trim();
      this.text = text == null ? "" : text.trim();
    }
  }
  @Override protected void onDestroy() {
    mainHandler.removeCallbacks(sessionTimeout);
    webView.destroy();
    networkIo.shutdownNow();
    thumbnailIo.shutdownNow();
    super.onDestroy();
  }
}
