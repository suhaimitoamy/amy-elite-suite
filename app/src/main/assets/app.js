document.addEventListener('DOMContentLoaded', () => {
  const mainContent = document.getElementById('main-content');
  const navBtns = document.querySelectorAll('.nav-btn');
  const site = 'https://suhaimitoamy.github.io/';

  const projects = [
    { id: 'indikator', title: 'Indikator TradingView', badge: 'Library', icon: 'chart', desc: 'Library indikator & file Pine Script', target: 'internal' },
    { id: 'jurnal', title: 'Jurnal Trading', badge: 'Jurnal', icon: 'journal', desc: 'Catat jurnal, evaluasi performa, dan riwayat trading', target: site + 'Build-aplikasi/index.html?v=20260517multiprovider' },
    { id: 'academy', title: 'Amy Trading Academy', badge: 'Learning', icon: 'academy', desc: 'Materi belajar trading dalam aplikasi', target: site + 'amy-trading-academy/' },
    { id: 'mapping', title: 'Aplikasi Mapping', badge: 'Mapping', icon: 'mapping', desc: 'Mapping market & chart untuk analisis peluang', target: site + 'ai-chart-analyzer-pwa/' }
  ];

  let indicators = [
    { name: 'Memuat data...', category: 'Loading', desc: 'Mengambil indikator dari repo...', code: 'Loading...' }
  ];

  let selectedIndicator = indicators[0];

  async function loadRepoIndicators() {
    try {
      const res = await fetch('https://api.github.com/repos/suhaimitoamy/Indikator-trading-view/contents/');
      if (!res.ok) throw new Error('API limit or network error');
      const data = await res.json();
      
      const repoIndicators = data
        .filter(item => item.name.endsWith('.pine'))
        .map(item => {
           let formattedName = item.name.replace('.pine', '').replace(/[-_]/g, ' ');
           formattedName = formattedName.replace(/\b\w/g, l => l.toUpperCase());
           return {
             name: formattedName,
             category: 'Pine Script',
             desc: `File sumber: ${item.name}`,
             url: item.download_url,
             code: ''
           };
        });

      if (repoIndicators.length > 0) {
         indicators = repoIndicators;
         selectedIndicator = indicators[0];
         if (document.getElementById('indicator-list')) {
            renderIndikator();
         }
      } else {
         indicators = [{ name: 'Kosong', category: 'Empty', desc: 'Tidak ada file .pine di repo.', code: 'Kosong' }];
         if (document.getElementById('indicator-list')) renderIndikator();
      }
    } catch (err) {
      console.error(err);
      indicators = [{ name: 'Error', category: 'Error', desc: 'Gagal mengambil data indikator.', code: 'Gagal' }];
      if (document.getElementById('indicator-list')) renderIndikator();
    }
  }

  loadRepoIndicators();

  const svgs = {
    chart: `<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--primary-gold)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 20V10M12 20V4M6 20v-6"></path><path d="M16 14h4v-2h-4zM10 8h4V6h-4zM4 16h4v-2H4z"></path><polyline points="4 14 10 8 16 14 22 4"></polyline></svg>`,
    journal: `<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--primary-gold)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path><path d="M17.5 7.5L12 13l-2.5-1.5L11 9l6.5-1.5z"></path></svg>`,
    academy: `<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--primary-gold)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22 10v6M2 10l10-5 10 5-10 5z"></path><path d="M6 12v5c3 3 9 3 12 0v-5"></path></svg>`,
    mapping: `<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--primary-gold)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21"></polygon><line x1="9" y1="3" x2="9" y2="18"></line><line x1="15" y1="6" x2="15" y2="21"></line><circle cx="12" cy="8" r="2"></circle><path d="M12 10v5"></path></svg>`,
    code: `<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="var(--primary-gold)" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 18 22 12 16 6"></polyline><polyline points="8 6 2 12 8 18"></polyline></svg>`
  };

  const badgeSvgs = {
    Library: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path></svg>`,
    Jurnal: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>`,
    Learning: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path></svg>`,
    Mapping: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path><circle cx="12" cy="10" r="3"></circle></svg>`
  };

  function icon(type) {
    return `<span class="app-icon ${type}">${svgs[type] || ''}</span>`;
  }

  function setActive(target) {
    navBtns.forEach(btn => btn.classList.toggle('active', btn.dataset.target === target));
  }

  function projectCard(item) {
    const badgeIcon = badgeSvgs[item.badge] || '';
    return `<button class="card project-card" data-open="${item.id}">${icon(item.icon)}<span class="card-content"><h3>${item.title}</h3><p>${item.desc}</p><span class="badge">${badgeIcon} ${item.badge}</span></span><span class="chevron">›</span></button>`;
  }

  function renderProjectList(title) {
    setActive(title === 'Beranda' ? 'beranda' : 'proyek');
    mainContent.innerHTML = `<div class="page-header"><h2>${title}</h2></div><div class="project-grid">${projects.map(projectCard).join('')}</div>`;
  }

  function renderKoleksi() {
    setActive('koleksi');
    mainContent.innerHTML = `<div class="page-header"><h2>Koleksi</h2></div><div class="collection-list"><button class="collection-item">Favorit</button><button class="collection-item">Riwayat Dibuka</button><button class="collection-item">Kode Tersimpan</button><button class="collection-item">Update Project</button></div>`;
  }

  function renderIndicatorList(category = 'Semua', query = '') {
    const list = document.getElementById('indicator-list');
    if (!list) return;
    const filtered = indicators.filter(item => (category === 'Semua' || item.category === category) && (item.name.toLowerCase().includes(query.toLowerCase()) || item.desc.toLowerCase().includes(query.toLowerCase())));
    list.innerHTML = filtered.map(item => {
      const originalIndex = indicators.indexOf(item);
      return `<button class="indicator-item" data-select-indicator="${originalIndex}">${icon('code')}<span><strong>${item.name}</strong><small>${item.desc}</small></span><span class="chevron">›</span></button>`;
    }).join('') || '<div class="empty">Indikator tidak ditemukan.</div>';
  }

  async function renderIndikator() {
    setActive('proyek');
    const categoryOptions = ['Semua', ...new Set(indicators.map(i => i.category))];
    const pillsHTML = categoryOptions.map(cat => `<button class="pill ${cat === 'Semua' ? 'active' : ''}" data-filter="${cat}">${cat}</button>`).join('');

    mainContent.innerHTML = `<div class="page-header row"><button class="back-btn" data-nav="proyek">‹</button><h2>Indikator TradingView</h2></div><input id="indicator-search" class="search-input" placeholder="Cari indikator..."><div class="pill-row">${pillsHTML}</div><div id="indicator-list" class="indicator-list"></div><section class="code-panel"><span class="badge">Terpilih</span><h3>${selectedIndicator.name}</h3><p>${selectedIndicator.desc}</p><pre id="code-display">${selectedIndicator.code || 'Mengambil source code dari GitHub...'}</pre><div class="actions"><button class="action-btn" data-save-code>Simpan Kode</button><button class="action-btn primary" data-copy-code>Salin Kode</button></div></section>`;
    
    renderIndicatorList();

    if (!selectedIndicator.code && selectedIndicator.url) {
       try {
         const res = await fetch(selectedIndicator.url);
         const text = await res.text();
         selectedIndicator.code = text;
         const codeDisplay = document.getElementById('code-display');
         if (codeDisplay) codeDisplay.textContent = text;
       } catch (err) {
         const codeDisplay = document.getElementById('code-display');
         if (codeDisplay) codeDisplay.textContent = 'Gagal memuat kode. Periksa koneksi internet.';
       }
    }
  }

  function openProject(id) {
    const project = projects.find(item => item.id === id);
    if (!project) return;
    if (project.target === 'internal') {
      renderIndikator();
    } else {
      location.assign(project.target);
    }
  }

  function navigate(target) {
    if (target === 'beranda') renderProjectList('Beranda');
    if (target === 'proyek') renderProjectList('Proyek');
    if (target === 'koleksi') renderKoleksi();
  }

  document.addEventListener('click', async event => {
    const openBtn = event.target.closest('[data-open]');
    const navBtn = event.target.closest('[data-nav]');
    const indicatorBtn = event.target.closest('[data-select-indicator]');
    const filterBtn = event.target.closest('[data-filter]');
    const copyBtn = event.target.closest('[data-copy-code]');
    const saveBtn = event.target.closest('[data-save-code]');
    if (openBtn) openProject(openBtn.dataset.open);
    if (navBtn) navigate(navBtn.dataset.nav);
    if (indicatorBtn) { selectedIndicator = indicators[Number(indicatorBtn.dataset.selectIndicator)]; renderIndikator(); }
    if (filterBtn) { document.querySelectorAll('.pill').forEach(item => item.classList.remove('active')); filterBtn.classList.add('active'); renderIndicatorList(filterBtn.dataset.filter, document.getElementById('indicator-search')?.value || ''); }
    if (copyBtn) { try { await navigator.clipboard.writeText(selectedIndicator.code); } catch (error) {} copyBtn.textContent = 'Tersalin'; }
    if (saveBtn) { localStorage.setItem('amy_saved_code', selectedIndicator.code); saveBtn.textContent = 'Tersimpan'; }
  });

  document.addEventListener('input', event => {
    if (event.target.id === 'indicator-search') {
      const activeFilter = document.querySelector('.pill.active')?.dataset.filter || 'Semua';
      renderIndicatorList(activeFilter, event.target.value);
    }
  });

  navBtns.forEach(btn => btn.addEventListener('click', () => navigate(btn.dataset.target)));
  navigate('beranda');
});
