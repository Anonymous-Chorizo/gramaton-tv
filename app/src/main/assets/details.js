(function (title) {
  const headings = Array.from(document.querySelectorAll('h1'));
  const heading = headings.find(h => h.textContent.trim() === title) || headings[headings.length - 1];
  let description = heading && heading.parentElement && heading.parentElement.parentElement ? heading.parentElement.parentElement.innerText.split('Select server:')[0].slice(0,900) : '';
  const synopsis = document.querySelector('.movie-description');
  if (synopsis) description = synopsis.textContent.trim().slice(0,900);
  const structured = [];
  for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
    try {
      const data = JSON.parse(script.textContent);
      structured.push(...(Array.isArray(data) ? data : data['@graph'] || [data]));
    } catch (_) {}
  }
  const media = structured.find(item => item && item.name === title &&
    [].concat(item['@type']).some(type => ['Movie', 'TVSeries', 'TVEpisode'].includes(type))) || {};
  const info = synopsis && synopsis.parentElement && synopsis.parentElement.parentElement;
  const labeled = label => {
    const lines = info ? info.innerText.split('Select server:')[0] : '';
    const match = lines.match(new RegExp('(?:^|\\n)\\s*(?:' + label + ')\\s*:\\s*([^\\n]+)', 'i'));
    return match ? match[1].trim() : '';
  };
  const imdbLinks = [].concat(media.sameAs || [], media.url || []);
  const imdb = imdbLinks.map(value => typeof value === 'string' &&
      value.match(/^https?:\/\/(?:www\.)?imdb\.com\/title\/(tt[0-9]+)(?:[/?#]|$)/i))
    .find(Boolean);
  const movieInfo = document.querySelector('.movie-info');
  const metadata = selector => movieInfo?.querySelector('.movie-data ' + selector)?.textContent.trim() || '';
  const rating = metadata('.imdb-rating');
  const runtime = metadata('.movie-time');
  const year = (String(metadata('.movie-date') || media.datePublished || labeled('Released|Release Date|Year')).match(/\b(?:18|19|20)\d{2}\b/) || [])[0];
  const genre = metadata('.movie-genre') || [].concat(media.genre || []).filter(Boolean).join(', ') || labeled('Genres?');
  const cast = [].concat(media.actor || []).map(actor => typeof actor === 'string' ? actor : actor && actor.name)
    .filter(Boolean).join(', ') || labeled('Starring|Cast|Actors?');
  const trailer = Array.from(document.querySelectorAll('.extra a.strip[href]')).find(link =>
    /^watch trailer$/i.test(link.textContent.trim()) && link.protocol === 'https:' &&
    link.hostname === 'www.youtube.com' && link.pathname === '/watch' &&
    /^[\w-]{11}$/.test(new URLSearchParams(link.search).get('v') || ''));
  const favoriteId = document.querySelector('.favorites-add')?.getAttribute('onclick')
    ?.match(/\baddFavorite\(['"](\d+)['"]\)/)?.[1];
  const container = document.getElementById('tv-episodes-list-container');
  const episodes = [];
  let season = 0, episode = 0;
  if (container) {
    for (const list of container.querySelectorAll('ol[id^="season"]')) {
      const s = Number(list.id.replace('season',''));
      for (const item of list.querySelectorAll('li[data-episode]')) episodes.push({season:s,episode:Number(item.getAttribute('data-episode')),title:item.textContent.trim()});
    }
    const selectedList = container.querySelector('ol.active');
    const selectedItem = selectedList && selectedList.querySelector('li.active[data-episode]');
    season = selectedList ? Number(selectedList.id.replace('season','')) : 0;
    episode = selectedItem ? Number(selectedItem.getAttribute('data-episode')) : 0;
    if (season && episode) description += '\nSeason ' + season + ', Episode ' + episode + ': ' + selectedItem.textContent.trim();
  }
  const art = Array.from(document.images).filter(image => !image.alt.trim() && image.naturalWidth > 200 && /^https:/.test(image.currentSrc));
  const backdrop = art.find(image => image.naturalWidth > image.naturalHeight);
  const poster = art.find(image => image.naturalWidth < image.naturalHeight);
  return JSON.stringify({backdrop:backdrop && backdrop.currentSrc,poster:poster && poster.currentSrc,ready:!!document.querySelector('a[href*="/player/videojs5.html"],a[href*="/player/native.html"]'),description,rating,runtime,year,imdb:imdb && imdb[1],genre,cast,trailer:trailer?.href,favoriteId,episodes,season,episode});
})(__TITLE__)
