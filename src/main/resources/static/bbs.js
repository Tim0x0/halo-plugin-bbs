/* BBS 前台共享脚本：下拉菜单 / 退出登录 / 相对时间 / 右栏目录 / 图片灯箱 / 分享复制。
   模板以 defer 引入，执行时 DOM 已就绪。 */
(function () {
  'use strict';

  /* 下拉菜单（用户菜单 / 排序 / 移动端分类）：
     data-drop-toggle 触发开合，点击面板外或面板内链接后关闭 */
  document.addEventListener('click', function (e) {
    var opened = document.querySelectorAll('.bbs-drop.open');
    var toggle = e.target.closest('[data-drop-toggle]');
    if (toggle) {
      var drop = toggle.closest('.bbs-drop');
      var willOpen = drop && !drop.classList.contains('open');
      opened.forEach(function (d) { d.classList.remove('open'); });
      if (willOpen) drop.classList.add('open');
      return;
    }
    opened.forEach(function (d) { d.classList.remove('open'); });
  });

  /* 相对时间：7 天内换算为「x 分钟/小时/天前」，更早保留绝对值；悬停显示绝对时间 */
  document.querySelectorAll('time.bbs-time[datetime]').forEach(function (el) {
    var t = new Date(el.getAttribute('datetime')).getTime();
    if (isNaN(t)) return;
    el.title = el.textContent.trim();
    var diff = Date.now() - t;
    var min = 60000;
    var hour = 3600000;
    var day = 86400000;
    if (diff < 0 || diff >= 7 * day) return;
    el.textContent = diff < min ? '刚刚'
      : diff < hour ? Math.floor(diff / min) + ' 分钟前'
        : diff < day ? Math.floor(diff / hour) + ' 小时前'
          : Math.floor(diff / day) + ' 天前';
  });

  /* 右栏目录：正文 h2/h3 ≥ 3 时填充显示（仅桌面右栏；移动端不放目录，对齐主流论坛），
     滚动时高亮当前章节 */
  var prose = document.querySelector('.prose');
  var side = document.getElementById('bbsSideToc');
  if (prose && side) {
    var heads = prose.querySelectorAll('h2, h3');
    if (heads.length >= 3) {
      var list = side.querySelector('.bbs-side-toc__list');
      heads.forEach(function (h, i) {
        if (!h.id) h.id = 'sec-' + (i + 1);
        var a = document.createElement('a');
        a.href = '#' + h.id;
        a.textContent = h.textContent;
        a.className = 'bbs-toc__item' + (h.tagName === 'H3' ? ' bbs-toc__item--sub' : '');
        list.appendChild(a);
      });
      side.classList.add('has-toc');

      // 标题进入视口上部阅读带（8%~30%）即视为当前节
      if ('IntersectionObserver' in window) {
        var io = new IntersectionObserver(function (entries) {
          entries.forEach(function (en) {
            if (!en.isIntersecting) return;
            list.querySelectorAll('a').forEach(function (a) {
              a.classList.toggle('is-active',
                a.getAttribute('href') === '#' + en.target.id);
            });
          });
        }, { rootMargin: '-8% 0px -70% 0px' });
        heads.forEach(function (h) { io.observe(h); });
      }
    }
  }

  /* 图片灯箱：点击正文图片放大，点击遮罩或 Esc 关闭 */
  var imgs = document.querySelectorAll('.prose img');
  if (imgs.length) {
    var overlay = document.createElement('div');
    overlay.className = 'bbs-lightbox';
    overlay.innerHTML = '<img alt=""/>';
    document.body.appendChild(overlay);
    var big = overlay.querySelector('img');
    imgs.forEach(function (img) {
      img.classList.add('bbs-zoomable');
      img.addEventListener('click', function () {
        big.src = img.currentSrc || img.src;
        overlay.classList.add('on');
      });
    });
    overlay.addEventListener('click', function () { overlay.classList.remove('on'); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') overlay.classList.remove('on');
    });
  }

  /* 分享：复制当前页链接（去掉锚点与查询串），按钮文案短暂切换作反馈 */
  document.querySelectorAll('.js-share').forEach(function (btn) {
    var label = btn.querySelector('span');
    var done = function () {
      if (!label) return;
      var old = label.textContent;
      label.textContent = '已复制链接';
      setTimeout(function () { label.textContent = old; }, 1600);
    };
    var fallbackCopy = function (url) {
      var ta = document.createElement('textarea');
      ta.value = url;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try {
        if (document.execCommand('copy')) done();
      } catch (e) { /* 忽略：极老浏览器无剪贴板能力 */ }
      ta.remove();
    };
    btn.addEventListener('click', function () {
      var url = location.origin + location.pathname;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(url).then(done, function () { fallbackCopy(url); });
      } else {
        fallbackCopy(url);
      }
    });
  });
  /* 问答帖已解决切换（详情页，仅作者可见）：调 UC API（Halo 会话 + CSRF），
     成功后刷新页面同步徽章与按钮态 */
  var solveBtn = document.querySelector('.js-solve');
  if (solveBtn) {
    solveBtn.addEventListener('click', function () {
      if (solveBtn.disabled) return;
      solveBtn.disabled = true;
      var name = solveBtn.getAttribute('data-post-name');
      var solved = solveBtn.getAttribute('data-solved') === 'true';
      var action = solved ? 'unsolve' : 'solve';
      var xsrf = (document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/) || [])[1];
      fetch('/apis/uc.api.bbs.timxs.com/v1alpha1/bbsposts/'
          + encodeURIComponent(name) + '/' + action, {
        method: 'PUT',
        headers: xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) } : {}
      }).then(function (res) {
        if (res.ok) { location.reload(); return; }
        solveBtn.disabled = false;
      }).catch(function () { solveBtn.disabled = false; });
    });
  }
  /* 锁定 / 关评论帖的只读评论：官方评论组件不渲染（其 shadow DOM 输入框无法隐藏），
     改走 Halo 公开评论 API 渲染历史评论——只读，无任何输入入口。
     支持「加载更多」分页与回复按需展开；内容按纯文本插入（textContent），无 XSS 面 */
  var roBox = document.getElementById('bbsRoComments');
  if (roBox) {
    var roName = roBox.getAttribute('data-post-name');
    var roBase = '/apis/api.halo.run/v1alpha1/comments';
    var roQuery = 'group=bbs.timxs.com&kind=BbsPost&version=v1alpha1&name='
      + encodeURIComponent(roName);
    var roPage = 1;
    var ROC_SIZE = 20;

    var roTime = function (iso) {
      var d = new Date(iso);
      if (isNaN(d.getTime())) return '';
      var p = function (n) { return n < 10 ? '0' + n : '' + n; };
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
        + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    };

    /* 单条评论 / 回复行：头像 + 名字 + 时间 + 纯文本内容 */
    var roItem = function (vo, isReply) {
      var row = document.createElement('div');
      row.className = 'bbs-roc__item' + (isReply ? ' bbs-roc__item--reply' : '');
      var ava = document.createElement('img');
      ava.className = 'bbs-roc__ava';
      ava.alt = '';
      if (vo.owner && vo.owner.avatar) ava.src = vo.owner.avatar;
      var body = document.createElement('div');
      body.className = 'bbs-roc__body';
      var head = document.createElement('div');
      head.className = 'bbs-roc__head';
      var name = document.createElement('b');
      name.textContent = (vo.owner && vo.owner.displayName) || '匿名';
      var time = document.createElement('span');
      time.textContent = roTime(vo.spec && vo.spec.creationTime);
      head.appendChild(name);
      head.appendChild(time);
      var content = document.createElement('div');
      content.className = 'bbs-roc__content';
      content.textContent = (vo.spec && vo.spec.content) || '';
      body.appendChild(head);
      body.appendChild(content);
      row.appendChild(ava);
      row.appendChild(body);
      return row;
    };

    /* 回复按需展开（一次拉全，上限 50 条足够只读场景） */
    var roReplies = function (commentName, count, mount) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'bbs-roc__more';
      btn.textContent = '展开 ' + count + ' 条回复';
      btn.addEventListener('click', function () {
        btn.disabled = true;
        fetch(roBase + '/' + encodeURIComponent(commentName) + '/reply?page=1&size=50')
          .then(function (r) { return r.json(); })
          .then(function (data) {
            var wrap = document.createElement('div');
            wrap.className = 'bbs-roc__replies';
            (data.items || []).forEach(function (vo) {
              wrap.appendChild(roItem(vo, true));
            });
            mount.replaceChild(wrap, btn);
          })
          .catch(function () { btn.disabled = false; });
      });
      mount.appendChild(btn);
    };

    var roLoad = function () {
      fetch(roBase + '?' + roQuery + '&page=' + roPage + '&size=' + ROC_SIZE)
        .then(function (r) { return r.json(); })
        .then(function (data) {
          var items = data.items || [];
          items.forEach(function (vo) {
            var cell = document.createElement('div');
            cell.className = 'bbs-roc__cell';
            cell.appendChild(roItem(vo, false));
            var replyCount = (vo.status && vo.status.replyCount) || 0;
            if (replyCount > 0) {
              roReplies(vo.metadata.name, replyCount, cell);
            }
            roBox.appendChild(cell);
          });
          var loaded = (roPage - 1) * ROC_SIZE + items.length;
          if ((data.total || 0) > loaded) {
            var more = document.createElement('button');
            more.type = 'button';
            more.className = 'bbs-roc__more';
            more.textContent = '加载更多评论';
            more.addEventListener('click', function () {
              roBox.removeChild(more);
              roPage += 1;
              roLoad();
            });
            roBox.appendChild(more);
          }
        })
        .catch(function () { /* 拉取失败静默：提示条已说明评论状态 */ });
    };
    roLoad();
  }
})();
