# TerrainDetector — ArenaSystem 0.2.8.6

Detector mengevaluasi heightmap hasil normalisasi. Ia tidak mengubah terrain.
Batas aksesibilitas utama adalah perbedaan tinggi maksimal **1 blok** antara
setiap dua kolom yang berbagi sisi (horizontal X atau Z).

## Metrik

- Rentang elevasi total.
- Perbedaan tinggi maksimum antar kolom bertetangga.
- Rata-rata perbedaan tinggi antar kolom.
- Selisih elevasi spawn RED/BLUE.
- Maksimum dan rata-rata perbedaan tinggi pada jalur tengah RED ke BLUE.

## Ambang penolakan

- Rentang elevasi lebih dari 48 blok.
- Selisih elevasi spawn RED/BLUE lebih dari 8 blok.
- Perbedaan tinggi pada satu pasangan kolom bertetangga lebih dari 1 blok.
- Perbedaan tinggi satu langkah pada jalur utama RED ke BLUE lebih dari 1 blok.

Peringatan diberikan untuk rentang elevasi lebih dari 28 blok dan selisih
elevasi spawn lebih dari 4 blok. Batas slope divalidasi kembali oleh detector
meskipun TerrainNormalizer sudah memiliki post-condition, sehingga heightmap
yang masuk dari jalur lain tetap tidak dapat melewati validasi ini.

Detector heightmap belum menganalisis line-of-sight, gua, overhang, material
licin, struktur buatan di seluruh arena, atau kelayakan semua jalur alternatif.
Pengecekan block entity dan ruang spawn/objektif dilakukan terpisah oleh
generator/plugin. Fairness seluruh spawn aktif, termasuk GREEN/YELLOW, masih
menjadi pekerjaan terrain-generation tahap berikutnya.
