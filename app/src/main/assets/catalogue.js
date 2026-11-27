(function () {
  const sectionIds = {
    'whatchlist-title': 'Watchlist', 'popular-this-week': 'Popular This Week',
    'recently-added-titles': 'Recently Added', 'new-series-title': 'Recent Episodes',
    'top-releases-this-year-title': '', 'favorites-list-title': 'Favorites'
  };
  const isHome = location.pathname === '/';
  const titleLink = a => { try { const u = new URL(a.href); return u.origin === location.origin && /^\/(movies|series)\/\d+[^/]*\/?$/.test(u.pathname); } catch (_) { return false; } };
  const cards = [], sections = [], seen = new Set();
  let section = 'Featured';
  for (const a of document.querySelectorAll('h1,h2,h3,h4,[id],a[href]')) {
    if (Object.prototype.hasOwnProperty.call(sectionIds, a.id)) {
      section = sectionIds[a.id] || a.textContent.replace(/\s*-?\s*View All\s*/i, '').trim();
      const viewAll = a.querySelector('a.view_all') || a.querySelector('a[href]');
      sections.push({label:section, url:viewAll ? viewAll.href : ''});
      continue;
    }
    if (a.tagName.toLowerCase() !== 'a' || !titleLink(a) || a.closest('.slick-cloned')) continue;
    const u = new URL(a.href), url = u.origin + u.pathname + (u.pathname.startsWith('/series/') ? u.search : '');
    const key = (isHome ? section + '|' : '') + url;
    if (seen.has(key)) continue;
    let box = a.closest('.item'), img = box && box.querySelector('img');
    if (!img) {
      box = a;
      for (let i = 0; i < 5 && box.parentElement; i++) {
        box = box.parentElement;
        const paths = new Set(Array.from(box.querySelectorAll('a[href]')).filter(titleLink).map(x => new URL(x.href).pathname));
        if (paths.size > 1) break;
        img = box.querySelector('img');
        if (img) break;
      }
    }
    if (!img) continue;
    const h = box.querySelector('h2'), title = (img.alt || (h && h.textContent) || '').trim();
    if (!title) continue;
    let image = '';
    try { image = new URL(img.getAttribute('data-src') || img.getAttribute('src') || '', location.href).href; } catch (_) {}
    const genre = box.querySelector('.movie-genre'), year = box.querySelector('.mf-year'), plot = box.querySelector('.featured-plot');
    const series = u.pathname.startsWith('/series/');
    let episode = '';
    if (series && u.searchParams.get('season') && u.searchParams.get('episode')) episode = 'S' + u.searchParams.get('season') + ' E' + u.searchParams.get('episode');
    cards.push({url, title, image, section, series, landscape:isHome && section==='Featured', description:plot ? plot.textContent.trim() : '',
      meta: [series ? 'TV series' : '', episode, year && year.textContent.trim(), genre && genre.textContent.trim()].filter(Boolean).join(' · ')});
    seen.add(key);
  }
  const links = Array.from(document.querySelectorAll('a'));
  const page = word => links.some(a => a.textContent.trim().toLowerCase() === word && !a.closest('.disabled') && a.getAttribute('aria-disabled') !== 'true');
  return JSON.stringify({cards, sections, home:isHome, ready:!isHome || sections.length >= 4,
    login:!!document.querySelector('input[type="password"]'), next:!isHome && page('next'), previous:!isHome && (page('prev') || page('previous')),
    empty: !cards.length && /no (movies|results|titles|records|items)|nothing found/i.test(document.body.innerText)});
})()
