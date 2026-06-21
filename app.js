document.addEventListener('DOMContentLoaded', () => {
  const mainContent = document.getElementById('main-content');
  const navBtns = document.querySelectorAll('.nav-btn');

  const projects = [
    { id: 'indikator', title: 'Indikator TradingView', badge: 'Library', icon: '📈' },
    { id: 'jurnal', title: 'Jurnal Trading', badge: 'Jurnal', icon: '📓' },
    { id: 'academy', title: 'Amy Trading Academy', badge: 'Learning', icon: '🎓' },
    { id: 'mapping', title: 'Aplikasi Mapping', badge: 'Mapping', icon: '🗺️' }
  ];

  const pages = {
    beranda: () => renderProjectList('Beranda', projects),
    proyek: () => renderProjectList('Proyek', projects),
    koleksi: () => renderKoleksi(),
    indikator: () => renderIndikator(),
    jurnal: () => renderWebView('Jurnal Trading'),
    academy: () => renderWebView('Amy Trading Academy'),
    mapping: () => renderWebView('Aplikasi Mapping')
  };

  function renderProjectList(title, items) {
    let html = `<div class="page-header"><h2>${title}</h2></div><div class="project-grid">`;
    items.forEach(item => {
      html += `
        <div class="card" onclick="navigate('${item.id}')">
          <div class="card-icon">${item.icon}</div>
          <div class="card-content">
            <h3>${item.title}</h3>
            <span class="badge">${item.badge}</span>
          </div>
        </div>
      `;
    });
    html += `</div>`;
    mainContent.innerHTML = html;
  }

  function renderKoleksi() {
    mainContent.innerHTML = `
      <div class="page-header"><h2>Koleksi</h2></div>
      <div class="collection-list">
        <div class="collection-item">Favorit</div>
        <div class="collection-item">Riwayat Dibuka</div>
        <div class="collection-item">Kode Tersimpan</div>
        <div class="collection-item">Update Project</div>
      </div>
    `;
  }

  function renderIndikator() {
    mainContent.innerHTML = `
      <div class="page-header">
        <button class="back-btn" onclick="navigate('beranda')">⬅ Kembali</button>
        <h2>Indikator TradingView</h2>
      </div>
      <div class="search-filter">
        <input type="text" placeholder="Search indikator..." />
        <select><option>Semua Kategori</option></select>
      </div>
      <div class="code-details">
        <h3>Detail Kode Pine Script</h3>
        <pre><code>// Example Pine Script\nstudy("My Indicator")\nplot(close)</code></pre>
        <div class="actions">
          <button class="action-btn">Salin Kode</button>
          <button class="action-btn">Simpan Kode</button>
        </div>
      </div>
    `;
  }

  function renderWebView(title) {
    mainContent.innerHTML = `
      <div class="page-header">
        <button class="back-btn" onclick="navigate('beranda')">⬅ Kembali</button>
        <h2>${title}</h2>
      </div>
      <div class="webview-container">
        <p>Halaman internal untuk ${title} sedang dimuat...</p>
      </div>
    `;
  }

  window.navigate = function(target) {
    if (pages[target]) {
      pages[target]();
      updateNav(target);
    }
  };

  function updateNav(target) {
    navBtns.forEach(btn => {
      if (btn.dataset.target === target) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });
  }

  navBtns.forEach(btn => {
    btn.addEventListener('click', (e) => {
      const target = e.currentTarget.dataset.target;
      navigate(target);
    });
  });

  // Initial load
  navigate('beranda');
});
