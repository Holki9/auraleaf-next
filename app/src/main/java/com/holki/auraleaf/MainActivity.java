package com.holki.auraleaf;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private DatabaseHelper dbHelper;
    private SharedPreferences prefs;
    private BookAdapter adapter;
    private RecyclerView recyclerBooks;
    private LinearLayout layoutEmptyState;
    private TextView tvEmptyTitle;
    private TextView tvEmptySubtitle;
    private EditText etSearch;

    // Header views
    private LinearLayout layoutMainHeader;
    private LinearLayout layoutFolderHeader;
    private View btnOpenPdf;
    private View btnAddGroup;
    private ImageView btnSettings;
    private ImageView btnFolderBack;
    private TextView tvFolderTitle;
    private TextView tvFolderCount;
    private View btnFolderAdd;
    private Button btnEmptyChoose;

    private long currentGroupId = 0; // 0 = root, >0 = inside group
    private String currentGroupTitle = "";

    private ActivityResultLauncher<String[]> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUI();

        setContentView(R.layout.activity_main);

        dbHelper = new DatabaseHelper(this);
        prefs = getSharedPreferences("auraleaf_prefs", MODE_PRIVATE);

        initViews();
        initPicker();
        initBackPress();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadItems();
    }

    private void hideSystemUI() {
        Window window = getWindow();
        window.getDecorView().setBackgroundColor(0xFF0A0C10);
        window.setStatusBarColor(0x00000000);
        window.setNavigationBarColor(0x00000000);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void initViews() {
        recyclerBooks = findViewById(R.id.recycler_books);
        layoutEmptyState = findViewById(R.id.layout_empty_state);
        tvEmptyTitle = findViewById(R.id.tv_empty_title);
        tvEmptySubtitle = findViewById(R.id.tv_empty_subtitle);
        etSearch = findViewById(R.id.et_search);

        layoutMainHeader = findViewById(R.id.layout_main_header);
        layoutFolderHeader = findViewById(R.id.layout_folder_header);
        btnOpenPdf = findViewById(R.id.btn_open_pdf);
        btnAddGroup = findViewById(R.id.btn_add_group);
        btnSettings = findViewById(R.id.btn_settings);

        btnFolderBack = findViewById(R.id.btn_folder_back);
        tvFolderTitle = findViewById(R.id.tv_folder_title);
        tvFolderCount = findViewById(R.id.tv_folder_count);
        btnFolderAdd = findViewById(R.id.btn_folder_add);
        btnEmptyChoose = findViewById(R.id.btn_empty_choose);

        recyclerBooks.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new BookAdapter(this, new BookAdapter.OnLibraryItemClickListener() {
            @Override
            public void onBookClick(BookItem book) {
                openReader(book);
            }

            @Override
            public void onBookRename(BookItem book) {
                showRenameBookDialog(book);
            }

            @Override
            public void onBookDelete(BookItem book) {
                showDeleteBookDialog(book);
            }

            @Override
            public void onBookMove(BookItem book) {
                showMoveBookDialog(book);
            }

            @Override
            public void onGroupClick(GroupItem group) {
                openGroup(group);
            }

            @Override
            public void onGroupRename(GroupItem group) {
                showRenameGroupDialog(group);
            }

            @Override
            public void onGroupDelete(GroupItem group) {
                showDeleteGroupDialog(group);
            }
        });
        recyclerBooks.setAdapter(adapter);

        btnOpenPdf.setOnClickListener(v -> launchPicker());
        btnFolderAdd.setOnClickListener(v -> launchPicker());
        btnEmptyChoose.setOnClickListener(v -> launchPicker());
        btnAddGroup.setOnClickListener(v -> showCreateGroupDialog());
        btnFolderBack.setOnClickListener(v -> openRootShelf());

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showAppSettingsDialog());
        }

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                loadItems();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void initBackPress() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentGroupId != 0) {
                    openRootShelf();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void openGroup(GroupItem group) {
        currentGroupId = group.id;
        currentGroupTitle = group.title;
        layoutMainHeader.setVisibility(View.GONE);
        layoutFolderHeader.setVisibility(View.VISIBLE);
        tvFolderTitle.setText(group.title);
        tvFolderCount.setText(formatCount(group.bookCount));
        etSearch.setText("");
        loadItems();
    }

    private void openRootShelf() {
        currentGroupId = 0;
        currentGroupTitle = "";
        layoutFolderHeader.setVisibility(View.GONE);
        layoutMainHeader.setVisibility(View.VISIBLE);
        etSearch.setText("");
        loadItems();
    }

    private void loadItems() {
        String query = etSearch.getText().toString().trim().toLowerCase();
        List<LibraryItem> displayItems = new ArrayList<>();

        if (currentGroupId == 0) {
            // Root view: show groups first, then root books
            List<GroupItem> groups = dbHelper.getGroups();
            for (GroupItem g : groups) {
                if (query.isEmpty() || g.title.toLowerCase().contains(query)) {
                    displayItems.add(g);
                }
            }

            List<BookItem> books = dbHelper.getRootBooks();
            for (BookItem b : books) {
                if (query.isEmpty() || b.title.toLowerCase().contains(query)) {
                    displayItems.add(b);
                }
            }

            boolean isEmpty = displayItems.isEmpty();
            layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            recyclerBooks.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            if (isEmpty) {
                tvEmptyTitle.setText("Ваша полка пуста");
                tvEmptySubtitle.setText("Добавьте комиксы (PDF, CBZ, CBR) или книги (EPUB, FB2, TXT).\nВсе файлы сохраняются на телефоне и доступны без интернета.");
            }
        } else {
            // Inside folder view: show only books in this group
            List<BookItem> books = dbHelper.getBooksInGroup(currentGroupId);
            tvFolderCount.setText(formatCount(books.size()));

            for (BookItem b : books) {
                if (query.isEmpty() || b.title.toLowerCase().contains(query)) {
                    displayItems.add(b);
                }
            }

            boolean isEmpty = displayItems.isEmpty();
            layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            recyclerBooks.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            if (isEmpty) {
                tvEmptyTitle.setText("Папка пуста");
                tvEmptySubtitle.setText("В этой папке пока нет файлов. Нажмите «Добавить», чтобы добавить книги сюда, или переместите их из главной полки.");
            }
        }

        adapter.setItems(displayItems);
    }

    private String formatCount(int count) {
        int rem10 = count % 10;
        int rem100 = count % 100;
        if (rem10 == 1 && rem100 != 11) {
            return count + " книга";
        } else if (rem10 >= 2 && rem10 <= 4 && (rem100 < 10 || rem100 >= 20)) {
            return count + " книги";
        } else {
            return count + " книг";
        }
    }

    private void showCreateGroupDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Новая папка");
        final EditText input = new EditText(this);
        input.setHint("Название папки (например: Блич, Романы)");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF64748B);
        int pad = dpToPx(16);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton("Создать", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                dbHelper.insertGroup(name);
                loadItems();
                Toast.makeText(this, "Папка «" + name + "» создана", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void showRenameGroupDialog(GroupItem group) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Переименовать папку");
        final EditText input = new EditText(this);
        input.setText(group.title);
        input.setTextColor(Color.WHITE);
        int pad = dpToPx(16);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                dbHelper.renameGroup(group.id, name);
                if (currentGroupId == group.id) {
                    currentGroupTitle = name;
                    tvFolderTitle.setText(name);
                }
                loadItems();
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void showDeleteGroupDialog(GroupItem group) {
        String[] options = new String[]{
                "Удалить только папку (книги останутся на полке)",
                "Удалить папку и все книги внутри"
        };

        new AlertDialog.Builder(this)
                .setTitle("Удалить папку «" + group.title + "»?")
                .setItems(options, (dialog, which) -> {
                    boolean deleteBooks = (which == 1);
                    dbHelper.deleteGroup(group.id, deleteBooks);
                    if (currentGroupId == group.id) {
                        openRootShelf();
                    } else {
                        loadItems();
                    }
                    Toast.makeText(this, "Папка удалена", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showMoveBookDialog(BookItem book) {
        List<GroupItem> groups = dbHelper.getGroups();
        List<String> titles = new ArrayList<>();
        List<Long> groupIds = new ArrayList<>();

        titles.add("📁 Главная полка (Без папки)");
        groupIds.add(0L);

        for (GroupItem g : groups) {
            titles.add("📁 " + g.title);
            groupIds.add(g.id);
        }

        titles.add("➕ Создать новую папку...");
        groupIds.add(-1L);

        new AlertDialog.Builder(this)
                .setTitle("Переместить «" + book.title + "»")
                .setItems(titles.toArray(new String[0]), (dialog, which) -> {
                    long selectedGroupId = groupIds.get(which);
                    if (selectedGroupId == -1L) {
                        // Create new group and move book to it
                        showCreateGroupAndMove(book);
                    } else {
                        dbHelper.setBookGroup(book.id, selectedGroupId);
                        loadItems();
                        Toast.makeText(this, "Перемещено", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showCreateGroupAndMove(BookItem book) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Создать папку и переместить");
        final EditText input = new EditText(this);
        input.setHint("Название папки");
        input.setTextColor(Color.WHITE);
        int pad = dpToPx(16);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton("Создать", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) {
                long newGid = dbHelper.insertGroup(name);
                dbHelper.setBookGroup(book.id, newGid);
                loadItems();
                Toast.makeText(this, "Перемещено в «" + name + "»", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void showRenameBookDialog(BookItem book) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Переименовать");
        final EditText input = new EditText(this);
        input.setText(book.title);
        input.setTextColor(Color.WHITE);
        int pad = dpToPx(16);
        input.setPadding(pad, pad, pad, pad);
        builder.setView(input);

        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            String newTitle = input.getText().toString().trim();
            if (!newTitle.isEmpty()) {
                dbHelper.renameBook(book.id, newTitle);
                loadItems();
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void showDeleteBookDialog(BookItem book) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить книгу?")
                .setMessage("«" + book.title + "» будет удалена с полки.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    dbHelper.deleteBook(book.id);
                    loadItems();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void initPicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Exception ignored) {}

                        importBook(uri);
                    }
                }
        );
    }

    private void launchPicker() {
        filePickerLauncher.launch(new String[]{
                "application/pdf",
                "application/x-cbz",
                "application/x-cbr",
                "application/vnd.comicbook+zip",
                "application/vnd.comicbook-rar",
                "application/epub+zip",
                "application/x-fictionbook+xml",
                "application/x-fictionbook",
                "application/x-fb2",
                "text/plain",
                "application/zip",
                "*/*"
        });
    }

    private void importBook(Uri uri) {
        Toast.makeText(this, "Импорт файла...", Toast.LENGTH_SHORT).show();

        long targetGroupId = currentGroupId;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String displayName = getFileName(uri);
                String title = displayName != null ? displayName.replaceFirst("[.][^.]+$", "") : "Книга";

                // Universal cover and page extraction (PDF, CBZ, CBR, EPUB, FB2, TXT)
                CoverExtractor.ComicInfo info = CoverExtractor.extract(this, uri, displayName);

                long bookId = dbHelper.insertBook(title, uri.toString(), info.totalPages, 0, info.coverPath, targetGroupId);

                runOnUiThread(() -> {
                    loadItems();
                    BookItem newBook = new BookItem(bookId, title, uri.toString(), info.totalPages, 0, info.coverPath, System.currentTimeMillis(), targetGroupId);
                    openReader(newBook);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка импорта: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result != null ? result.lastIndexOf('/') : -1;
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private void openReader(BookItem book) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        intent.putExtra(ReaderActivity.EXTRA_URI, book.uriString);
        intent.putExtra(ReaderActivity.EXTRA_LAST_PAGE, book.lastPage);
        startActivity(intent);
    }

    private void showAppSettingsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_settings);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvBlurStatus = dialog.findViewById(R.id.tv_blur_status);
        TextView tvBlurPercent = dialog.findViewById(R.id.tv_blur_percent);
        SeekBar seekBlur = dialog.findViewById(R.id.seek_blur);

        TextView chipOff = dialog.findViewById(R.id.chip_blur_off);
        TextView chipLight = dialog.findViewById(R.id.chip_blur_light);
        TextView chipMedium = dialog.findViewById(R.id.chip_blur_medium);
        TextView chipStrong = dialog.findViewById(R.id.chip_blur_strong);

        SwitchCompat switchHaptic = dialog.findViewById(R.id.switch_haptic);
        SwitchCompat switchSound = dialog.findViewById(R.id.switch_sound);
        SwitchCompat switchTwoPage = dialog.findViewById(R.id.switch_two_page);

        View btnDone = dialog.findViewById(R.id.btn_settings_done);
        View btnCloseIcon = dialog.findViewById(R.id.btn_settings_close_icon);

        int currentBlur = prefs.getInt("blur_level", 25);
        seekBlur.setProgress(currentBlur);
        updateBlurStatusText(tvBlurStatus, tvBlurPercent, currentBlur);

        seekBlur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateBlurStatusText(tvBlurStatus, tvBlurPercent, progress);
                    prefs.edit().putInt("blur_level", progress).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        chipOff.setOnClickListener(v -> {
            seekBlur.setProgress(0);
            updateBlurStatusText(tvBlurStatus, tvBlurPercent, 0);
            prefs.edit().putInt("blur_level", 0).apply();
        });
        chipLight.setOnClickListener(v -> {
            seekBlur.setProgress(25);
            updateBlurStatusText(tvBlurStatus, tvBlurPercent, 25);
            prefs.edit().putInt("blur_level", 25).apply();
        });
        chipMedium.setOnClickListener(v -> {
            seekBlur.setProgress(50);
            updateBlurStatusText(tvBlurStatus, tvBlurPercent, 50);
            prefs.edit().putInt("blur_level", 50).apply();
        });
        chipStrong.setOnClickListener(v -> {
            seekBlur.setProgress(100);
            updateBlurStatusText(tvBlurStatus, tvBlurPercent, 100);
            prefs.edit().putInt("blur_level", 100).apply();
        });

        switchHaptic.setChecked(prefs.getBoolean("haptic_enabled", true));
        switchHaptic.setOnCheckedChangeListener((btn, isChecked) ->
                prefs.edit().putBoolean("haptic_enabled", isChecked).apply());

        switchSound.setChecked(prefs.getBoolean("sound_enabled", true));
        switchSound.setOnCheckedChangeListener((btn, isChecked) ->
                prefs.edit().putBoolean("sound_enabled", isChecked).apply());

        switchTwoPage.setChecked(prefs.getBoolean("two_page_landscape", true));
        switchTwoPage.setOnCheckedChangeListener((btn, isChecked) ->
                prefs.edit().putBoolean("two_page_landscape", isChecked).apply());

        btnDone.setOnClickListener(v -> dialog.dismiss());
        btnCloseIcon.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void updateBlurStatusText(TextView tvStatus, TextView tvPercent, int progress) {
        tvPercent.setText(progress + "%");
        if (progress == 0) {
            tvStatus.setText("Выключено (без размытия)");
        } else if (progress <= 25) {
            tvStatus.setText("Легкое (рекомендуется)");
        } else if (progress <= 60) {
            tvStatus.setText("Среднее (мягкий глянец)");
        } else {
            tvStatus.setText("Сильное (глубокий туман)");
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
