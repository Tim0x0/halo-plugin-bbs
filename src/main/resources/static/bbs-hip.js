/* 互动装扮引导（设置开启时由模板引入，defer）：
   先批量拉取页面所需的用户身份数据，再动态注入 interaction-plus runtime。
   数据服务两类消费方：
   1. hip-* 组件：回填 data 属性（data 优先于 user-name），组件注册时数据已就位，
      列表页 N 个作者只发 1 个批量请求，也避免「先骨架后补全」的闪变；
   2. 列表昵称 .bbs-uname[data-user-name]：列表不用身份行组件（密度考量，只保留
      昵称样式 + 最高优先级身份标识），由本脚本直接应用颜色/渐变并插入小标识。
   任一环节失败都照常注入 runtime，hip 组件自行按 user-name 单发兜底；
   interaction-plus 未安装 / 停用时 runtime 404，hip-* 保持未注册，
   由 bbs.css 的 :not(:defined){display:contents} 让原生兜底内容直接显示，
   .bbs-uname 则保持纯文本昵称。 */
(function () {
  var loadRuntime = function () {
    var s = document.createElement('script');
    s.src = '/plugins/interaction-plus/assets/runtime/interaction-plus.runtime.js';
    document.head.appendChild(s);
  };

  var hipEls = document.querySelectorAll(
    'hip-user-avatar[user-name], hip-user-identity[user-name], hip-user-card[user-name]');
  var unameEls = document.querySelectorAll('.bbs-uname[data-user-name]');

  var names = [];
  var collect = function (n) {
    if (n && names.indexOf(n) < 0) names.push(n);
  };
  hipEls.forEach(function (el) { collect(el.getAttribute('user-name')); });
  unameEls.forEach(function (el) { collect(el.getAttribute('data-user-name')); });

  if (!names.length) {
    loadRuntime();
    return;
  }

  /* 昵称样式：solid=单色；gradient=渐变文字；未知模式回退首色 */
  var applyNameStyle = function (el, ns) {
    if (!ns || !ns.colors || !ns.colors.length) return;
    if (ns.mode === 'gradient' && ns.colors.length > 1) {
      el.style.backgroundImage = 'linear-gradient(90deg,' + ns.colors.join(',') + ')';
      el.style.webkitBackgroundClip = 'text';
      el.style.backgroundClip = 'text';
      el.style.color = 'transparent';
    } else {
      el.style.color = ns.colors[0];
    }
  };

  /* 身份标识（仅取优先级最高一枚）：优先图标，加载失败回退小描边文字 */
  var buildTextMark = function (mark) {
    var s = document.createElement('span');
    s.className = 'bbs-uname__mark-text';
    s.textContent = mark.displayName || '';
    if (mark.color) {
      s.style.color = mark.color;
    }
    return s;
  };
  var buildMark = function (mark) {
    var wrap = document.createElement('span');
    wrap.className = 'bbs-uname__mark';
    if (mark.icon) {
      var img = document.createElement('img');
      img.src = mark.icon;
      img.alt = mark.displayName || '';
      img.title = mark.displayName || '';
      img.loading = 'lazy';
      img.onerror = function () {
        wrap.textContent = '';
        wrap.appendChild(buildTextMark(mark));
      };
      wrap.appendChild(img);
    } else if (mark.displayName) {
      wrap.appendChild(buildTextMark(mark));
    }
    return wrap;
  };

  fetch('/apis/api.interaction-plus.timxs.com/v1alpha1/identities', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    // 批量接口单次上限 50；超出部分不回填，对应 hip 组件注入 runtime 后自行单发
    body: JSON.stringify({ userNames: names.slice(0, 50) })
  }).then(function (r) {
    return r.ok ? r.json() : null;
  }).then(function (data) {
    if (!data || !data.items) return;
    var map = {};
    data.items.forEach(function (it) {
      if (it && it.userName) map[it.userName] = it;
    });
    hipEls.forEach(function (el) {
      var it = map[el.getAttribute('user-name')];
      if (it) el.setAttribute('data', JSON.stringify(it));
    });
    unameEls.forEach(function (el) {
      var it = map[el.getAttribute('data-user-name')];
      if (!it) return;
      var deco = it.decorations || {};
      applyNameStyle(el, deco.nameStyle && deco.nameStyle.nameStyle);
      var marks = (it.identityMarks || []).slice()
        .sort(function (a, b) { return (a.priority || 0) - (b.priority || 0); });
      if (marks.length) {
        el.insertAdjacentElement('afterend', buildMark(marks[0]));
      }
    });
  }).catch(function () {}).finally(loadRuntime);
})();
