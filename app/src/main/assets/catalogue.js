(function () {
  const sectionIds = {
    'whatchlist-title': 'Watchlist', 'popular-this-week': 'Popular This Week',
    'recently-added-titles': 'Recently Added', 'new-series-title': 'Recent Episodes',
    'top-releases-this-year-title': '', 'favorites-list-title': 'Favorites'
  };
  const params = new URLSearchParams(location.search);
  const query = params.get('q') || params.get('search') || '';
  const isHome = location.pathname === '/' && !query;
  const titleLink = a => { try { const u = new URL(a.href); return u.origin === location.origin && /^\/(movies|series)\/\d+[^/]*\/?$/.test(u.pathname); } catch (_) { return false; } };
  const cards = [], sections = [], seen = new Set();
  let section = 'Featured';
  const listing = isHome ? document : document.querySelector('#content .item-container') || document;
  for (const a of listing.querySelectorAll('h1,h2,h3,h4,[id],a[href]')) {
    if (Object.prototype.hasOwnProperty.call(sectionIds, a.id)) {
      section = sectionIds[a.id] || a.textContent.replace(/\s*-?\s*View All\s*/i, '').trim();
      const viewAll = a.querySelector('a.view_all[href]');
      const canOpenListing = isHome && viewAll && viewAll.origin === location.origin &&
        (viewAll.pathname !== location.pathname || viewAll.search !== location.search) && !titleLink(viewAll);
      sections.push({label:section, url:canOpenListing ? viewAll.href : ''});
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
    const releaseYear = year ? year.textContent.trim() : '';
    const genres = genre ? genre.textContent.trim() : '';
    const series = u.pathname.startsWith('/series/');
    let episode = '';
    if (series && u.searchParams.get('season') && u.searchParams.get('episode')) episode = 'S' + u.searchParams.get('season') + ' E' + u.searchParams.get('episode');
    cards.push({url, title, image, year:releaseYear, genre:genres, section, series, landscape:isHome && section==='Featured', description:plot ? plot.textContent.trim() : '',
      meta: [series ? 'TV series' : '', episode, releaseYear, genres].filter(Boolean).join(' · ')});
    seen.add(key);
  }
  const nextLink = !isHome && Array.from(document.querySelectorAll('.pagination a')).find(a =>
    (a.dataset.type === 'next' || a.textContent.trim().toLowerCase() === 'next') &&
    !a.closest('.disabled') && a.getAttribute('aria-disabled') !== 'true');
  const nextPage = nextLink && Number(nextLink.getAttribute('onclick')?.match(/\((\d+)\)/)?.[1]) || 0;
  const page = Number(params.get('page')) || (nextPage > 1 ? nextPage - 1 : 0);
  const hasFilters = /^\/(movies|series)\/?$/.test(location.pathname);
  const sorts = Array.from(document.querySelectorAll('.sortby-filter li .option-value'), option => {
    const label = option.textContent.trim();
    return {label, value:label};
  });
  const genres = Array.from(document.querySelectorAll('.genre-filter input.checkbox[value]'), input => ({
    label:input.labels[0]?.textContent.trim() || input.value,
    value:input.value.replace(/_/g, ' ')
  }));
  const filters = hasFilters ? {
    sort:{options:sorts, current:params.get('sortby') || document.querySelector('.sortby-filter .value')?.textContent.trim() || ''},
    genre:{options:genres.length ? [{label:'All genres', value:''}, ...genres] : [], current:params.get('genres') || ''}
  } : null;
  const spinner = !isHome && document.getElementById('spinner');
  const updating = spinner && spinner.getClientRects().length > 0;
  const emptyListing = !isHome && document.readyState === 'complete' && !!document.querySelector('#content .item-container');
  return JSON.stringify({cards, sections, filters, query, page, nextPage, home:isHome, ready:isHome ? sections.length >= 4 : !updating,
    login:!!document.querySelector('input[type="password"]'), next:!!nextLink,
    empty: !cards.length && !updating && (emptyListing || /no (movies|results|titles|records|items)|nothing found/i.test(document.body.innerText))});
})()
