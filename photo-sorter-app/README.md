# Photo Sorter (Android)

An Android app that sorts the photos on your phone into albums, either
**by location** (where each photo was taken) or **by content** (what's in it).
It works on Android 10 and newer.

## Install

1. On your phone, open the repository's **Releases** page on GitHub and pick
   **Photo Sorter (latest build)**. Every push that changes the app rebuilds it.
2. Tap `PhotoSorter.apk` to download it, then open it.
3. Android will ask you to allow installing apps from your browser.
   Allow it, then tap **Install**.

The APK is signed with a throwaway debug key that changes with each build.
To install a newer build, uninstall the old one first.

## Use

1. Allow photo access. Also allow **location** access when Android asks.
   Otherwise Android hides the GPS data in your photos and location sorting
   finds nothing.
2. Pick an album (it defaults to **Camera**) and a mode:
   - **By location**: photos are grouped by city, region or country.
     Place names come from Android's geocoder, which needs internet.
     Offline, photos are grouped by rounded coordinates instead.
     Photos with no GPS data go to *Unknown location*.
   - **By content**: Google's ML Kit labels each photo on the phone itself
     (no upload, ~400 labels such as Dog, Cat, Beach, Food, Flower).
     Each photo goes to its strongest label. To get only the albums you care
     about, list labels like `Dog, Cat`; everything else goes to *Other*.
3. Tap **Preview albums**. Nothing on your phone changes yet.
4. Tap **Copy into albums** to keep your originals in place, or
   **Move into albums** (Android 11+, asks for confirmation).
   The albums appear under `Pictures/Sorted/` in your gallery app.

Already-sorted photos in `Pictures/Sorted/` are skipped the next time you sort.

## Build it yourself

```bash
cd photo-sorter-app
./gradlew assembleDebug    # needs JDK 17 and the Android SDK (ANDROID_HOME)
# -> app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow `.github/workflows/android-app.yml` does the same
thing and publishes the APK as the `photo-sorter-latest` release.

## Code

| File | What it does |
|---|---|
| `PhotoStore.kt` | Reads albums, photos and EXIF GPS from MediaStore; copies and moves files |
| `Groupers.kt` | `LocationGrouper` (Geocoder) and `ContentGrouper` (ML Kit labeling) |
| `SorterViewModel.kt` | Runs the preview, copy and move steps in the background |
| `MainActivity.kt` | The Compose UI and permission requests |
