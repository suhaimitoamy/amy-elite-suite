const fs = require('fs');

const filesToScan = ['index.html', 'app.js'];
let combinedText = '';
filesToScan.forEach(file => {
  if (fs.existsSync(file)) {
    combinedText += fs.readFileSync(file, 'utf8') + '\n';
  }
});

const required = [
  "Amy Elite Suite",
  "VIP Facility",
  "Indikator TradingView",
  "Jurnal Trading",
  "Amy Trading Academy",
  "Aplikasi Mapping",
  "Beranda",
  "Proyek",
  "Koleksi",
  "Learning"
];

const forbidden = [
  "AI Chart Analyzer App",
  "Catatan Cepat",
  "Profil App"
];

let failed = false;

required.forEach(text => {
  if (!combinedText.includes(text)) {
    console.error(`Missing required text: ${text}`);
    failed = true;
  }
});

forbidden.forEach(text => {
  if (combinedText.includes(text)) {
    console.error(`Found forbidden text: ${text}`);
    failed = true;
  }
});

if (failed) {
  process.exit(1);
} else {
  console.log("Tests passed!");
  process.exit(0);
}
