(function (id, operation) {
  const request = {done:false, selected:null};
  window.gramatonFavoriteRequest = request;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10000);
  const read = async (path, options = {}) => {
    const response = await fetch(path, {
      ...options,
      cache:'no-store',
      redirect:'error',
      signal:controller.signal
    });
    if (!response.ok) throw new Error('Favorites request failed');
    return response.text();
  };
  const isFavorite = async () => {
    const parse = html => new DOMParser().parseFromString(html, 'text/html');
    let page = 1;
    let list = parse(await read('/favorites')).querySelector('#content .item-container');
    if (!list) throw new Error('Favorites listing unavailable');
    while (true) {
      if (list.querySelector('#favorite-item-' + id)) return true;
      const next = Number(list.querySelector('.pagination a[data-type="next"]')
        ?.getAttribute('onclick')?.match(/^favoritesPage\((\d+)\)/)?.[1]);
      if (!(next > page)) return false;
      list = parse(await read('/favorites', {
        method:'POST', headers:{'X-Requested-With':'XMLHttpRequest'},
        body:new URLSearchParams({page:next, token:token_key})
      }));
      page = next;
    }
  };
  (async () => {
    if (operation === 'read') return isFavorite();
    await read('/favorites/' + operation, {
      method:'POST', headers:{'X-Requested-With':'XMLHttpRequest'}, body:new URLSearchParams({id})
    });
    return operation === 'add';
  })().then(selected => { request.selected = selected; }, () => {}).finally(() => {
    clearTimeout(timeout);
    request.done = true;
  });
})(__ID__, __OPERATION__)
