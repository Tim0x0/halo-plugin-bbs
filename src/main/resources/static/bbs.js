/* BBS 前台共享脚本：下拉菜单 / 退出登录 / 相对时间 / 右栏目录 /
   图片灯箱 / 分享复制。模板以 defer 引入，执行时 DOM 已就绪。 */
(function () {
  'use strict';

  /* 无头像字母占位：对齐 Halo（渲染层派生）+ Flarum stringToColor（色相算法）。
     种子 = 显示名 UTF-16 码元累加 % 360；HSV(h, 0.3, 0.9) 浅底白字。
     SSR 模板只写 data-bbs-letter=显示名，这里填字母与底色；只读评论 DOM 同样走此函数。 */
  var ANON_COLOR = '#94a3b8';
  var hsvToHex = function (h, s, v) {
    var i = Math.floor(h * 6);
    var f = h * 6 - i;
    var p = v * (1 - s);
    var q = v * (1 - f * s);
    var t = v * (1 - (1 - f) * s);
    var r, g, b;
    switch (i % 6) {
      case 0: r = v; g = t; b = p; break;
      case 1: r = q; g = v; b = p; break;
      case 2: r = p; g = v; b = t; break;
      case 3: r = p; g = q; b = v; break;
      case 4: r = t; g = p; b = v; break;
      default: r = v; g = p; b = q;
    }
    var hex = function (n) {
      var s = Math.floor(n * 255).toString(16);
      return s.length < 2 ? '0' + s : s;
    };
    return '#' + hex(r) + hex(g) + hex(b);
  };
  var avatarColorFrom = function (seed) {
    if (!seed || !String(seed).trim()) return ANON_COLOR;
    var num = 0;
    var s = String(seed);
    for (var i = 0; i < s.length; i++) num += s.charCodeAt(i);
    var hue = ((num % 360) + 360) % 360;
    return hsvToHex(hue / 360, 0.3, 0.9);
  };
  var avatarLetterFrom = function (seed) {
    if (!seed || !String(seed).trim()) return '?';
    return String(seed).charAt(0).toUpperCase();
  };
  var paintLetterEl = function (el, seed) {
    if (!el) return;
    var name = seed != null ? seed : (el.getAttribute('data-bbs-letter') || '');
    el.textContent = avatarLetterFrom(name);
    el.style.background = avatarColorFrom(name);
    el.removeAttribute('data-bbs-letter');
  };
  document.querySelectorAll('[data-bbs-letter]').forEach(function (el) {
    paintLetterEl(el);
  });

  /* 窄屏顶栏菜单手风琴：多级子行按 data-bbs-depth 拍平渲染且默认收起。
     有子行的行挂箭头按钮：箭头展开 / 收起子树（子孙行可见性=所有祖先均已展开），
     行自身保留原语义——带链接的点文字跳转，不带链接的整行即开关。
     （对齐官方主题「父项链接可点」；官方移动端全展开，这里手风琴更适合长菜单） */
  (function () {
    var rows = Array.prototype.slice.call(
      document.querySelectorAll('.bbs-hm-m__panel .bbs-hm-m__row'));
    if (!rows.length) return;
    var depthOf = function (row) {
      return parseInt(row.getAttribute('data-bbs-depth') || '0', 10);
    };
    var refresh = function () {
      var stack = [];
      rows.forEach(function (row) {
        var d = depthOf(row);
        while (stack.length && stack[stack.length - 1].depth >= d) stack.pop();
        var visible = stack.every(function (s) { return s.open; });
        if (row.hasAttribute('data-bbs-depth')) {
          row.classList.toggle('is-shown', visible);
        }
        if (row.classList.contains('bbs-hm-m__row--parent')) {
          stack.push({ depth: d, open: row.classList.contains('is-open') });
        }
      });
    };
    rows.forEach(function (row, i) {
      var next = rows[i + 1];
      if (!next || depthOf(next) <= depthOf(row)) return;
      row.classList.add('bbs-hm-m__row--parent');
      var caret = document.createElement('span');
      caret.className = 'bbs-hm-m__caret';
      caret.setAttribute('role', 'button');
      caret.setAttribute('aria-label', '展开/收起子菜单');
      caret.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="2.5" stroke-linecap="round"><path d="m6 9 6 6 6-6"/></svg>';
      var toggle = function () {
        row.classList.toggle('is-open');
        caret.classList.toggle('is-open');
        refresh();
      };
      caret.addEventListener('click', function (e) {
        // 箭头可能嵌在链接行内：拦截默认跳转，只开合
        e.preventDefault();
        e.stopPropagation();
        toggle();
      });
      row.appendChild(caret);
      if (row.tagName !== 'A') {
        // 无链接父行：整行即开关（箭头点击已 stopPropagation，不会双触发）
        row.addEventListener('click', function () {
          toggle();
        });
      }
    });
    refresh();
  })();

  /* 下拉菜单（用户菜单 / 排序 / 移动端分类 / 顶栏多级菜单）：
     data-drop-toggle 触发开合。顶栏子菜单只关同级，保留祖先；其它下拉互斥。 */
  document.addEventListener('click', function (e) {
    var toggle = e.target.closest('[data-drop-toggle]');
    if (toggle) {
      var drop = toggle.closest('.bbs-drop');
      if (!drop) return;
      var willOpen = !drop.classList.contains('open');
      var inHeader = drop.closest('.bbs-hm, .bbs-hm-m');
      if (inHeader) {
        var parent = drop.parentElement;
        if (parent) {
          parent.querySelectorAll(':scope > .bbs-drop.open').forEach(function (d) {
            if (d !== drop) d.classList.remove('open');
          });
        }
        drop.querySelectorAll('.bbs-drop.open').forEach(function (d) {
          d.classList.remove('open');
        });
      } else {
        document.querySelectorAll('.bbs-drop.open').forEach(function (d) {
          d.classList.remove('open');
        });
      }
      if (willOpen) drop.classList.add('open');
      else drop.classList.remove('open');
      e.stopPropagation();
      return;
    }
    document.querySelectorAll('.bbs-drop.open').forEach(function (d) {
      d.classList.remove('open');
    });
  });

  /* 时间显示统一由服务端渲染（BbsTimeFormats + 后台 dateFormat 设置），
     此处不做客户端改写——时间格式以服务端渲染为准 */

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
  /* 锁定帖的只读评论：官方评论组件不渲染（其 shadow DOM 输入框无法隐藏），
     改走 BBS 自建端点渲染历史评论——只读，无任何输入入口。
     评论「加载更多」分页；楼中楼随列表预取前几条，超出的点「展开剩余」再分页。
     内容按 HTML 插入（Halo Comment.spec.content 写入时已净化，详见 roItem） */
  var roBox = document.getElementById('bbsRoComments');
  if (roBox) {
    var roName = roBox.getAttribute('data-post-name');
    // 评论 + 回复都走 BBS 自建端点（owner.name 仅 User kind 返回，供 hip-user-avatar 拉装扮；
    // Halo 公开评论 API 清空 name 防 email 泄露，但致装扮不可拉）
    var roBase = '/apis/api.bbs.timxs.com/v1alpha1/posts/' + encodeURIComponent(roName) + '/comments';
    var roReplyBase = '/apis/api.bbs.timxs.com/v1alpha1/comments';
    var roPage = 1;
    var ROC_SIZE = 20;
    // 展开楼中楼后每页拉取条数（列表内预取的前几条由后端 RO_REPLY_PREVIEW 决定）
    var ROC_REPLY_PAGE = 10;

    /* 评论时间跟随后台 dateFormat。相对时间的阈值刻意与服务端
       BbsTimeFormats.relative() 对齐（<60s 刚刚 / <60min 分钟 / <24h 小时 /
       <30d 天 / <12mo 月 / 年）——同一页面两处时间不能各说各话 */
    var roFmt = roBox.getAttribute('data-date-format') || 'relative';
    var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    var roAbs = function (d, pattern) {
      var ymd = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
      if (pattern === 'MM-dd') return pad(d.getMonth() + 1) + '-' + pad(d.getDate());
      if (pattern === 'yyyy-MM-dd') return ymd;
      // 兜底 yyyy-MM-dd HH:mm，同时用作 tooltip
      return ymd + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    };
    var roRelative = function (d) {
      var sec = Math.floor((Date.now() - d.getTime()) / 1000);
      if (sec < 0) return roAbs(d, 'yyyy-MM-dd HH:mm');
      if (sec < 60) return '刚刚';
      var m = Math.floor(sec / 60);
      if (m < 60) return m + ' 分钟前';
      var h = Math.floor(m / 60);
      if (h < 24) return h + ' 小时前';
      var day = Math.floor(h / 24);
      if (day < 30) return day + ' 天前';
      var mo = Math.floor(day / 30);
      return mo < 12 ? mo + ' 个月前' : Math.floor(mo / 12) + ' 年前';
    };
    var roTime = function (iso) {
      var d = new Date(iso);
      if (isNaN(d.getTime())) return '';
      return roFmt === 'relative' ? roRelative(d) : roAbs(d, roFmt);
    };

    /* 楼主 username：评论者与之相同则挂「楼主」标签（社区感最强的一处，成本最低）。
       作者链接模板同帖子页（空则昵称不可点） */
    var roOwner = roBox.getAttribute('data-owner-name') || '';
    var roAuthorTpl = roBox.getAttribute('data-author-link') || '';
    var roHip = roBox.getAttribute('data-hip') === 'true';
    /* 楼层号：楼主占 1 楼，评论从 2 楼起。置顶楼不占号（钉替号，见 roItem），
       故计数器只对非置顶自增，序号连续不留洞；跨页累加不重置 */
    var roSeq = 2;

    /* 头像：有 owner.name（User kind）走 hip-user-card > hip-user-avatar，
       与楼主帖同款（可点弹名片 + 拉装扮头像框）；未装 interaction-plus 时
       两层包装 display:contents 降级，里面的原生头像直接顶上。
       无 name（Email kind 匿名评论，后端不返 name 防邮箱泄露）字母兜底 */
    /* 字母头像：前台按显示名哈希（与页顶 SSR 占位同一套 avatarColorFrom），
       不跟分类色、不跟主题色。同一人在列表 / 楼主 / 评论颜色一致 */
    var roLetterAva = function (cls, owner) {
      var letter = document.createElement('span');
      letter.className = cls + ' bbs-ava--letter';
      paintLetterEl(letter, (owner && owner.displayName) || '');
      return letter;
    };

    var roAvatar = function (vo, isReply) {
      var cls = 'bbs-ava ' + (isReply ? 'bbs-ava--rep' : 'bbs-ava--flr');
      var name = vo.owner && vo.owner.name;
      if (!name) {
        return roLetterAva(cls, vo.owner);
      }
      var card = document.createElement('hip-user-card');
      card.setAttribute('user-name', name);
      var host = document.createElement('hip-user-avatar');
      host.setAttribute('user-name', name);
      host.setAttribute('scene', isReply ? 'comment reply' : 'comment');
      var inner;
      if (vo.owner.avatar) {
        inner = document.createElement('img');
        inner.className = cls;
        inner.alt = '';
        inner.src = vo.owner.avatar;
      } else {
        inner = roLetterAva(cls, vo.owner);
      }
      host.appendChild(inner);
      card.appendChild(host);
      return card;
    };

    /* 昵称：有 owner.name 套 hip-user-identity（称号 / 勋章渲染在这里，
       所以行内别的标记一律不贴昵称旁边，会被读成用户徽章）。
       hip 开启时身份行自己按 userCardLinkTemplate 跳转，外面不套 a；
       关闭时仍按作者链接模板整体可点 */
    var roNameEl = function (vo, isReply) {
      var display = (vo.owner && vo.owner.displayName) || '匿名';
      var name = vo.owner && vo.owner.name;
      var cls = isReply ? 'bbs-rep__name' : 'bbs-flr__name';
      if (name && roHip) {
        var wrap = document.createElement('span');
        wrap.className = cls;
        var ident = document.createElement('hip-user-identity');
        ident.setAttribute('user-name', name);
        ident.setAttribute('scene', 'comment');
        var text = document.createElement('span');
        text.textContent = display;
        ident.appendChild(text);
        wrap.appendChild(ident);
        return wrap;
      }
      var href = name && roAuthorTpl
        ? roAuthorTpl.replace('{name}', encodeURIComponent(name))
        : '';
      var el = document.createElement(href ? 'a' : 'span');
      el.className = cls;
      if (href) el.href = href;
      el.textContent = display;
      return el;
    };

    var roIsOp = function (vo) {
      return !!(roOwner && vo.owner && vo.owner.name === roOwner);
    };

    /* 对齐官方评论插件未点赞态：MingCute heart-line。锁定帖只读，不用实心红心 */
    var SVG_UP = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
      + ' stroke-width="2" stroke-linejoin="round" aria-hidden="true">'
      + '<path d="M12 5.705c-3.692-3.947-9.114-.478-8.998 4.666q.102 4.59 7.19 8.8'
      + 'c.274.163.706.41 1.08.62a1.48 1.48 0 0 0 1.457 0c.373-.21.805-.457 1.08-.62'
      + 'q7.085-4.21 7.19-8.8c.115-5.144-5.307-8.613-8.999-4.666Z"/></svg>';
    var SVG_REPLY = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"'
      + ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
      + '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>';
    var SVG_PIN = '<svg viewBox="0 0 384 512" fill="currentColor" aria-hidden="true">'
      + '<path d="M32 32C32 14.3 46.3 0 64 0L320 0c17.7 0 32 14.3 32 32s-14.3 32-32 32l-29.5 0'
      + ' 11.4 148.2c36.7 19.9 65.7 53.2 79.5 94.7l1 3c3.3 9.8 1.6 20.5-4.4 28.8s-15.7 13.3-26'
      + ' 13.3L32 352c-10.3 0-19.9-4.9-26-13.3s-7.7-19-4.4-28.8l1-3c13.8-41.5 42.8-74.8'
      + ' 79.5-94.7L93.5 64 64 64C46.3 64 32 49.7 32 32zM160 384l64 0 0 96c0 17.7-14.3 32-32'
      + ' 32s-32-14.3-32-32l0-96z"/></svg>';

    /* 单条楼层（桌面 / 窄屏同一套，对齐 Bilibili / 知乎楼中楼）：
       头像 + 昵称[楼主] + 右上置顶钉 + prose 正文（「回复 @」插在第一段开头同行）
       + 动作栏(时间左，赞 / 回复数 / 楼层号右)。回复降一档：小头像 */
    var roItem = function (vo, isReply) {
      var row = document.createElement('div');
      row.className = isReply ? 'bbs-rep' : 'bbs-flr';
      var main = document.createElement('div');
      main.className = isReply ? 'bbs-rep__main' : 'bbs-flr__main';
      var head = document.createElement('div');
      head.className = isReply ? 'bbs-rep__head' : 'bbs-flr__head';

      head.appendChild(roNameEl(vo, isReply));
      if (roIsOp(vo)) {
        var op = document.createElement('span');
        op.className = 'bbs-op';
        op.textContent = '楼主';
        head.appendChild(op);
      }
      /* 置顶是楼层状态，不是身份：钉挂身份行右上（margin-left:auto），
         不贴昵称旁（会被读成用户徽章、跟装扮抢）。楼中楼不置顶。 */
      if (!isReply && vo.top === true) {
        var pin = document.createElement('span');
        pin.className = 'bbs-bic bbs-bic--pin';
        pin.title = '置顶';
        pin.setAttribute('aria-label', '置顶');
        pin.innerHTML = SVG_PIN;
        head.appendChild(pin);
      }
      main.appendChild(head);

      var iso = vo.creationTime || (vo.spec && vo.spec.creationTime);
      var time = document.createElement('time');
      time.textContent = roTime(iso);
      var isoDate = new Date(iso);
      if (!isNaN(isoDate.getTime())) {
        time.title = roAbs(isoDate, 'yyyy-MM-dd HH:mm');
        time.setAttribute('datetime', iso);
      }

      var upvote = vo.upvote || (vo.stats && vo.stats.upvote) || 0;
      var replyCount = vo.replyCount || (vo.status && vo.status.replyCount) || 0;

      /* 正文按 HTML 渲染 + 直接挂 .prose：引用 / 代码块 / 图片 / 表格与帖子正文同一套排版。
         Halo Comment.spec.content 文档定义为「Rendered HTML content」，写入时已净化
         （spec.raw 才是原始输入），无需前端再净化 */
      var content = document.createElement('div');
      content.className = (isReply ? 'bbs-rep__body' : 'bbs-flr__body') + ' prose';
      content.innerHTML = vo.content || (vo.spec && vo.spec.content) || '';

      /* 回复目标：后端按 Reply.spec.quoteReply 回表解析出被回复者（详见 fetchQuotes）。
         直接回复评论、或引用目标已删除 / 未审核时后端不返 quote，这里自然不渲染。
         「回复 @」是正文引导语：插进第一个块前面，inline 跟第一段同一行
         （Bilibili / 知乎楼中楼）。纯文本不做链接——上色会抢昵称的注意力 */
      if (isReply && vo.quote && vo.quote.displayName) {
        var to = document.createElement('span');
        to.className = 'bbs-rep__to';
        to.textContent = '回复 @' + vo.quote.displayName;
        var first = content.firstElementChild;
        /* 只插进 <p>：跟第一句同行。ul/pre/img/table 不能当 phrasing 父级，
           插进去是非法 HTML，改挂正文容器开头（会单独占一行，评论里少见） */
        if (first && first.tagName === 'P') {
          first.insertBefore(to, first.firstChild);
        } else {
          content.insertBefore(to, content.firstChild);
        }
      }
      main.appendChild(content);

      /* 动作栏：时间左，计数 / 楼号右（space-between）。
         赞始终显示（含 0）；回复数 0 不画。
         置顶楼不占楼层号：号表达「第几个发的」，置顶被提到最前会对不上。
         楼中楼无回复数 / 楼号，变成「时间左、赞右」。 */
      var foot = document.createElement('div');
      foot.className = isReply ? 'bbs-rep__foot' : 'bbs-flr__foot';
      foot.appendChild(time);

      var actions = document.createElement('span');
      actions.className = isReply ? 'bbs-rep__actions' : 'bbs-flr__actions';

      var up = document.createElement('span');
      up.className = isReply ? 'bbs-rep__up' : 'bbs-flr__stat';
      up.title = upvote + ' 人赞过';
      up.innerHTML = SVG_UP;
      var upCount = document.createElement('span');
      upCount.textContent = String(upvote);
      up.appendChild(upCount);
      actions.appendChild(up);

      if (!isReply) {
        if (replyCount) {
          var rc = document.createElement('span');
          rc.className = 'bbs-flr__stat';
          rc.title = replyCount + ' 条回复';
          rc.innerHTML = SVG_REPLY;
          var replyCountText = document.createElement('span');
          replyCountText.textContent = String(replyCount);
          rc.appendChild(replyCountText);
          actions.appendChild(rc);
        }
        if (vo.top !== true) {
          var anchor = 'c-' + (vo.name || roSeq);
          row.id = anchor;
          var no = document.createElement('a');
          no.className = 'bbs-flr__no';
          no.href = '#' + anchor;
          no.textContent = '#' + roSeq;
          no.title = '第 ' + roSeq + ' 楼';
          actions.appendChild(no);
          roSeq += 1;
        }
      }

      foot.appendChild(actions);
      main.appendChild(foot);

      row.appendChild(roAvatar(vo, isReply));
      row.appendChild(main);
      return row;
    };

    /* 楼中楼「展开剩余」：列表已带前几条（RoCommentVo.replies），这里只管超出的部分。
       首次点击重新拉第 1 页整体替换预取的几条（避免与预取重复），之后逐页追加。
       按钮就放在回复块内部末尾，故清空时用 insertBefore 保持它始终垫底 */
    var roReplies = function (commentName, wrap, rest) {
      var loadedPage = 0;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'bbs-more';
      btn.textContent = '展开剩余 ' + rest + ' 条回复';
      btn.addEventListener('click', function () {
        btn.disabled = true;
        var next = loadedPage + 1;
        fetch(roReplyBase + '/' + encodeURIComponent(commentName)
            + '/replies?page=' + next + '&size=' + ROC_REPLY_PAGE)
          .then(function (r) { return r.json(); })
          .then(function (data) {
            if (next === 1) {
              wrap.innerHTML = '';
              wrap.appendChild(btn);
            }
            (data.items || []).forEach(function (vo) {
              wrap.insertBefore(roItem(vo, true), btn);
            });
            loadedPage = next;
            var remain = (data.total || 0) - (wrap.children.length - 1);
            if (remain > 0) {
              btn.textContent = '展开剩余 ' + remain + ' 条回复';
              btn.disabled = false;
            } else {
              wrap.removeChild(btn);
            }
          })
          .catch(function () { btn.disabled = false; });
      });
      wrap.appendChild(btn);
    };

    var appendFloor = function (vo, before) {
      var floor = roItem(vo, false);
      var replyCount = vo.replyCount || 0;
      if (replyCount > 0) {
        var wrap = document.createElement('div');
        wrap.className = 'bbs-flr__replies';
        (vo.replies || []).forEach(function (r) {
          wrap.appendChild(roItem(r, true));
        });
        if (replyCount > (vo.replies || []).length) {
          roReplies(vo.name, wrap, replyCount - (vo.replies || []).length);
        }
        floor.querySelector('.bbs-flr__main').appendChild(wrap);
      }
      if (before) {
        roBox.insertBefore(floor, before);
      } else {
        roBox.appendChild(floor);
      }
    };

    var appendEnd = function (total) {
      var end = document.createElement('div');
      end.className = 'bbs-cmts__end';
      end.textContent = '— 已是全部 ' + total + ' 条回复 —';
      roBox.appendChild(end);
    };

    var roLoad = function () {
      fetch(roBase + '?page=' + roPage + '&size=' + ROC_SIZE)
        .then(function (r) { return r.json(); })
        .then(function (data) {
          var items = data.items || [];
          var total = data.total || 0;
          if (!total && roPage === 1) {
            var empty = document.createElement('div');
            empty.className = 'bbs-cmts__empty';
            empty.textContent = '这个帖子在锁定前没有任何回复';
            roBox.appendChild(empty);
            return;
          }
          items.forEach(function (vo) { appendFloor(vo); });
          var loaded = (roPage - 1) * ROC_SIZE + items.length;
          if (total > loaded) {
            var more = document.createElement('button');
            more.type = 'button';
            more.className = 'bbs-more';
            more.textContent = '加载更多评论';
            more.addEventListener('click', function () {
              more.disabled = true;
              var next = roPage + 1;
              fetch(roBase + '?page=' + next + '&size=' + ROC_SIZE)
                .then(function (r) { return r.json(); })
                .then(function (moreData) {
                  (moreData.items || []).forEach(function (vo) { appendFloor(vo, more); });
                  roPage = next;
                  var loadedNow = (roPage - 1) * ROC_SIZE + (moreData.items || []).length;
                  if ((moreData.total || 0) > loadedNow) {
                    more.disabled = false;
                  } else {
                    roBox.removeChild(more);
                    appendEnd(moreData.total || 0);
                  }
                })
                .catch(function () { more.disabled = false; });
            });
            roBox.appendChild(more);
          } else {
            // 归档帖滚到底要有收口；不重复「已锁定」文案（那句由上方说明条独占）
            appendEnd(total);
          }
        })
        .catch(function () { /* 首屏失败静默：上方说明条已交代评论状态 */ });
    };
    roLoad();
  }
})();
