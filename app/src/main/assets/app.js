document.addEventListener('DOMContentLoaded', () => {
  const mainContent = document.getElementById('main-content');
  const navBtns = document.querySelectorAll('.nav-btn');

  const projects = [
    {
      id: 'indikator',
      title: 'Indikator TradingView',
      badge: 'Library',
      icon: 'chart',
      desc: 'Library indikator & file Pine Script',
      repo: 'https://github.com/suhaimitoamy/Indikator-trading-view'
    },
    {
      id: 'jurnal',
      title: 'Jurnal Trading',
      badge: 'Jurnal',
      icon: 'journal',
      desc: 'Catat jurnal, evaluasi performa, dan riwayat trading',
      repo: 'https://github.com/suhaimitoamy/Jurnal-trading'
    },
    {
      id: 'academy',
      title: 'Amy Trading Academy',
      badge: 'Learning',
      icon: 'academy',
      desc: 'Materi belajar trading dalam aplikasi',
      repo: 'https://github.com/suhaimitoamy/amy-trading-academy'
    },
    {
      id: 'mapping',
      title: 'Aplikasi Mapping',
      badge: 'Mapping',
      icon: 'mapping',
      desc: 'Mapping market & chart untuk analisis peluang',
      repo: 'https://github.com/suhaimitoamy/ai-chart-analyzer-pwa'
    }
  ];

  const indicators = [
    { name: 'SMC Structure Pro', category: 'SMC', desc: 'Deteksi BOS, CHOCH, liquidity', code: '//@version=5\nindicator("SMC Structure Pro", overlay=true)\nplot(close)' },
    { name: 'Order Block Finder', category: 'SMC', desc: 'Zona OB bullish & bearish', code: '//@version=5\nindicator("Order Block Finder", overlay=true)\nplot(open)' },
    { name: 'Session Marker WIB', category: 'Session', desc: 'Killzone London & New York', code: '//@version=5\nindicator("Session Marker WIB", overlay=true)\nbgcolor(color.new(color.yellow, 90))' },
    { name: 'FVG Detector', category: 'FVG', desc: 'Fair Value Gap otomatis', code: '//@version=5\nindicator("FVG Detector", overlay=true)\nplot(high)' }
  ];

  let selectedIndicator = indicators[0];

  function icon(type) {
    return `<span class="app-icon ${type}"><i></i><b></b></span>`;
  }

  function setActive(target) {
    navBtns.forEach(btn => btn.classList.toggle('active', btn.dataset.target === target));
  }

  function projectCard(item) {
    return `
      <button class="card project-card" data-open="${item.id}">
        ${icon(item.icon)}
        <span class="card-content">
          <h3>${item.title}</h3>
          <p>${item.desc}</p>
          <span class="badge">${item.badge}</span>
        </span>
        <span class="chevron">›</span>
      </button>
    `;
  }

  function renderProjectList(title) {
    setActive(title === 'Beranda' ? 'beranda' : 'proyek');
    mainContent.innerHTML = `
      <div class="page-header"><h2>${title}</h2></div>
      <div class="project-grid">${projects.map(projectCard).join('')}</div>
    `;
  }

  function renderKoleksi() {
    setActive('koleksi');
    mainContent.innerHTML = `
      <div class="page-header"><h2>Koleksi</h2></div>
      <div class="collection-list">
        <button class="collection-item">Favorit</button>
        <button class="collection-item">Riwayat Dibuka</button>
        <button class="collection-item">Kode Tersimpan</button>
        <button class="collection-item">Update Project</button>
      </div>
    `;
  }

  function renderIndicatorList(category = 'Semua', query = '') {
    const list = document.getElementById('indicator-list');
    if (!list) return;
    const filtered = indicators.filter(item => {
      const byCategory = category === 'Semua' || item.category === category;
      const byQuery = item.name.toLowerCase().includes(query.toLowerCase()) || item.desc.toLowerCase().includes(query.toLowerCase());
      return byCategory && byQuery;
    });
    list.innerHTML = filtered.map((item, index) => `
      <button class="indicator-item" data-select-indicator="${index}">
        ${icon('code')}
        <span><strong>${item.name}</strong><small>${item.desc}</small></span>
        <span class="chevron">›</span>
      </button>
    `).join('') || '<div class="empty">Indikator tidak ditemukan.</div>';
  }

  function renderIndikator() {
    setActive('proyek');
    mainContent.innerHTML = `
      <div class="page-header row">
        <button class="back-btn" data-nav="proyek">‹</button>
        <h2>Indikator TradingView</h2>
      </div>
      <input id="indicator-search" class="search-input" placeholder="Cari indikator...">
      <div class="pill-row">
        <button class="pill active" data-filter="Semua">Semua</button>
        <button class="pill" data-filter="SMC">SMC</button>
        <button class="pill" data-filter="Session">Session</button>
        <button class="pill" data-filter="FVG">FVG</button>
      </div>
      <div id="indicator-list" class="indicator-list"></div>
      <section class="code-panel">
        <span class="badge">Terpilih</span>
        <h3>${selectedIndicator.name}</h3>
        <p>${selectedIndicator.desc}</p>
        <pre>${selectedIndicator.code}</pre>
        <div class="actions">
          <button class="action-btn" data-save-code>Simpan Kode</button>
          <button class="action-btn primary" data-copy-code>Salin Kode</button>
        </div>
      </section>
    `;
    renderIndicatorList();
  }

  function renderInternalProject(project) {
    setActive('proyek');
    mainContent.innerHTML = `
      <div class="page-header row">
        <button class="back-btn" data-nav="proyek">‹</button>
        <h2>${project.title}</h2>
      </div>
      <section class="internal-page">
        ${icon(project.icon)}
        <h3>${project.title}</h3>
        <p>${project.desc}</p>
        <div class="repo-box">
          <strong>Koneksi repo</strong>
          <span>${project.repo}</span>
        </div>
        <div class="webview-box">
          <strong>${project.badge} internal</strong>
          <p>Halaman ini sudah tersambung ke target repo dan siap dijadikan WebView/PWA internal.</p>
        </div>
      </section>
    `;
  }

  function openProject(id) {
    const project = projects.find(item => item.id === id);
    if (!project) return;
    if (id === 'indikator') renderIndikator();
    else renderInternalProject(project);
  }

  function navigate(target) {
    if (target === 'beranda') renderProjectList('Beranda');
    if (target === 'proyek') renderProjectList('Proyek');
    if (target === 'koleksi') renderKoleksi();
  }

  document.addEventListener('click', async (event) => {
    const openBtn = event.target.closest('[data-open]');
    const navBtn = event.target.closest('[data-nav]');
    const indicatorBtn = event.target.closest('[data-select-indicator]');
    const filterBtn = event.target.closest('[data-filter]');
    const copyBtn = event.target.closest('[data-copy-code]');
    const saveBtn = event.target.closest('[data-save-code]');

    if (openBtn) openProject(openBtn.dataset.open);
    if (navBtn) navigate(navBtn.dataset.nav);
    if (indicatorBtn) {
      selectedIndicator = indicators[Number(indicatorBtn.dataset.selectIndicator)];
      renderIndikator();
    }
    if (filterBtn) {
      document.querySelectorAll('.pill').forEach(item => item.classList.remove('active'));
      filterBtn.classList.add('active');
      renderIndicatorList(filterBtn.dataset.filter, document.getElementById('indicator-search')?.value || '');
    }
    if (copyBtn) {
      try { await navigator.clipboard.writeText(selectedIndicator.code); } catch (error) {}
      copyBtn.textContent = 'Tersalin';
    }
    if (saveBtn) {
      localStorage.setItem('amy_saved_code', selectedIndicator.code);
      saveBtn.textContent = 'Tersimpan';
    }
  });

  document.addEventListener('input', (event) => {
    if (event.target.id === 'indicator-search') {
      const activeFilter = document.querySelector('.pill.active')?.dataset.filter || 'Semua';
      renderIndicatorList(activeFilter, event.target.value);
    }
  });

  navBtns.forEach(btn => btn.addEventListener('click', () => navigate(btn.dataset.target)));
  navigate('beranda');
});
