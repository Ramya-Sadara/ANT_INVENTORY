# ANT INVENTORY — Android App

A barcode-friendly inventory app for counting physical stock across warehouse locations (RZD1–RZD8).

## Features

- Scan / type **Order No**, **Part No**, **Quantity** and **Location**
- Saves entries locally on the device (no internet needed)
- **Search** entries live by Order, Part or Location
- **Delete** individual entries (with confirmation)
- **Clear All** entries at once
- **Export CSV** — share the full inventory sheet via email, Drive, WhatsApp, etc.

---

## How to get the APK (no Android Studio needed)

### Step 1 — Put the project on GitHub

1. Go to [github.com](https://github.com) and sign in (or create a free account).
2. Click **New repository** → name it `ANT_INVENTORY` → click **Create repository**.
3. Upload the entire `ANT_INVENTORY-1` folder contents:
   - On the new repo page click **uploading an existing file**
   - Drag-and-drop all files/folders, then click **Commit changes**.

   > **Tip:** If you have Git installed you can also run these commands in the `ANT_INVENTORY-1` folder:
   > ```
   > git init
   > git add .
   > git commit -m "Initial commit"
   > git remote add origin https://github.com/YOUR_USERNAME/ANT_INVENTORY.git
   > git push -u origin main
   > ```

### Step 2 — Watch GitHub Actions build the APK

1. On your repository page click the **Actions** tab.
2. You will see a workflow called **Build APK** running automatically.
3. Wait ~3–5 minutes for it to finish (green ✓).

### Step 3 — Download the APK

1. Click the finished **Build APK** run.
2. Scroll to the bottom of the page under **Artifacts**.
3. Click **ANT-INVENTORY-debug** to download a `.zip`.
4. Unzip it — inside is `app-debug.apk`.

### Step 4 — Install on your Android phone

1. Copy `app-debug.apk` to your Android phone (USB, email, Google Drive, etc.).
2. On the phone go to **Settings → Apps → Special app access → Install unknown apps**.
3. Allow your file manager or browser to install apps.
4. Open the APK file and tap **Install**.

---

## Project structure

```
ANT_INVENTORY/
├── app/
│   └── src/main/
│       ├── java/com/ant/inventory/MainActivity.kt   ← app logic
│       ├── res/layout/activity_main.xml              ← main screen
│       ├── res/layout/item_entry.xml                 ← entry card
│       ├── res/drawable/                             ← shape backgrounds
│       ├── res/xml/file_paths.xml                    ← FileProvider config
│       └── AndroidManifest.xml
├── build.gradle.kts
└── gradlew / gradlew.bat
.github/workflows/build.yml                          ← GitHub Actions CI
```

## Locations supported

`RZD1` `RZD2` `RZD3` `RZD4` `RZD5` `RZD6` `RZD7` `RZD8` `Other`
