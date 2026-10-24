/* ============================================================
   Web For Tomato · 手机端适配脚本（由 App 的 WebView 注入）
   作用：把宽表格转成「一行一张卡片」的竖排布局。
   —— 表头文字被写到每个单元格的 data-label 上，CSS 再用
      ::before 显示出来；表头和列顺序完全不依赖上游模板。
   —— 动态渲染的表格（搜索/任务/历史/书库）由 MutationObserver
      持续补标，SPA 路由切换后依然有效。
   —— 脚本失败时表格不会有 .tomato-cards 类，自动退化为横滚表格，
      不会出现「没有列名的堆叠」。
   ============================================================ */
(function () {
  if (window.__tomatoMobileReady) return;
  window.__tomatoMobileReady = true;

  var TABLE_SEL = 'table.table';
  var LABEL_ATTR = 'data-label';

  function labelTable(table) {
    var heads = table.querySelectorAll('thead th');
    if (!heads.length) return;
    var labels = [];
    for (var i = 0; i < heads.length; i++) {
      labels.push((heads[i].textContent || '').replace(/\s+/g, ' ').trim());
    }
    var rows = table.querySelectorAll('tbody tr');
    for (var r = 0; r < rows.length; r++) {
      var row = rows[r];
      if (row.classList.contains('empty-row')) continue;
      var cells = row.children;
      for (var c = 0; c < cells.length && c < labels.length; c++) {
        if (labels[c]) cells[c].setAttribute(LABEL_ATTR, labels[c]);
      }
    }
    if (!table.classList.contains('tomato-cards')) table.classList.add('tomato-cards');
  }

  var pending = [];
  var scheduled = false;

  function flush() {
    scheduled = false;
    var list = pending;
    pending = [];
    for (var i = 0; i < list.length; i++) {
      try { labelTable(list[i]); } catch (e) { /* 单个表格失败不影响其它 */ }
    }
  }

  function schedule(table) {
    if (!table) return;
    if (pending.indexOf(table) !== -1) return;
    pending.push(table);
    if (!scheduled) {
      scheduled = true;
      if (window.requestAnimationFrame) requestAnimationFrame(flush);
      else setTimeout(flush, 16);
    }
  }

  function tableOf(node) {
    var el = node && node.nodeType === 1 ? node : (node && node.parentElement);
    if (!el || !el.closest) return null;
    return el.closest(TABLE_SEL);
  }

  function scanAll() {
    var tables = document.querySelectorAll(TABLE_SEL);
    for (var i = 0; i < tables.length; i++) schedule(tables[i]);
  }

  function boot() {
    scanAll();
    var mo = new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var rec = records[i];
        var t = tableOf(rec.target);
        if (t) { schedule(t); continue; }
        // 新增的节点里也可能带表格
        if (rec.addedNodes && rec.addedNodes.length) {
          for (var n = 0; n < rec.addedNodes.length; n++) {
            var node = rec.addedNodes[n];
            if (node.nodeType !== 1) continue;
            if (node.matches && node.matches(TABLE_SEL)) schedule(node);
            else if (node.querySelectorAll) {
              var inner = node.querySelectorAll(TABLE_SEL);
              for (var k = 0; k < inner.length; k++) schedule(inner[k]);
            }
          }
        }
      }
    });
    mo.observe(document.body, { childList: true, subtree: true, characterData: false });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
