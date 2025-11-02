
    let editingId = null;

    async function fetchWithTimeout(url, ms=15000, options={}){
      const controller = new AbortController();
      const id = setTimeout(()=>controller.abort(), ms);
      try{
        const res = await fetch(url, { ...options, signal: controller.signal });
        clearTimeout(id);
        return res;
      }catch(e){ clearTimeout(id); throw e; }
    }
    async function loadEffective(){
      const date = document.getElementById('date').value;
      if(!date){ alert('日付を選択してください'); return; }
      try{
        const res = await fetchWithTimeout(`/api/demand/effective?date=${date}`);
        const j = await res.json();
        renderTable('effective', '当日適用', j.data||[]);
      }catch(e){
        document.getElementById('effective').innerHTML = `<div class='muted'>読み込みに失敗しました</div>`;
      }
    }
    async function loadAll(){
      try{
        const res = await fetchWithTimeout(`/api/demand`);
        const j = await res.json();
        renderTable('all', 'すべて', j.data||[]);
      }catch(e){
        document.getElementById('all').innerHTML = `<div class='muted'>読み込みに失敗しました</div>`;
      }
    

    async function tidyOrder(){
      try{
        const res = await fetch('/api/demand/sort', { method:'POST' });
        if(!res.ok){
          try{ const j=await res.json(); alert(j.message||'整頓に失敗しました'); }catch(_){ alert('整頓に失敗しました'); }
          return;
        }
        await loadAll();
        if(document.getElementById('date').value){ await loadEffective(); }
      }catch(_){ alert('整頓に失敗しました'); }
    }

    async function tidyOrder(){
      try{
        const res = await fetch('/api/demand/sort', { method:'POST' });
        if(!res.ok){
          try{ const j=await res.json(); alert(j.message||'整頓に失敗しました'); }catch(_){ alert('整頓に失敗しました'); }
          return;
        }
        await loadAll();
        if(document.getElementById('date').value){ await loadEffective(); }
      }catch(_){ alert('整頓に失敗しました'); }
    }

    function renderTable(id, title, rows){
      const el = document.getElementById(id);
      if(!rows.length){ el.innerHTML = `<h4>${title}</h4><div class='muted'>データなし</div>`; return; }
      let html = `<h4>${title}</h4><table><thead><tr><th style=\"display:none;\">ID</th><th></th><th>日付</th><th>曜日</th><th>時間</th><th>必要</th><th>スキル</th><th style="display:none;">並び順</th><th>操作</th></tr></thead><tbody>`;
      for(const r of rows){
        const id = r.id;
        const del = `<button data-id=\"${id}\" class=\"btn-del\" style=\"background:#ef4444;color:#fff;border:none;border-radius:6px;padding:4px 8px;\">削除</button>`;
        const up = `<button data-id=\"${id}\" data-dir=\"up\" class=\"btn-move\" style=\"margin-left:6px;\">↑</button>`;
        const down = `<button data-id=\"${id}\" data-dir=\"down\" class=\"btn-move\" style=\"margin-left:4px;\">↓</button>`;
        const edit = `<button data-id=\"${id}\" class=\"btn-edit\" style=\"margin-left:6px;\">編集</button>`;
          html += `<tr data-id="${id}" data-order="${r.sortOrder||''}">`+
                  `<td style="display:none;" class="col-id">${id||''}</td>`+
                  `<td>${r.date||''}</td><td>${r.dayOfWeek||''}</td>`+
                  `<td>${r.startTime} - ${r.endTime}</td><td>${r.requiredSeats}</td>`+
                  `<td>${r.skill? (r.skill.name||r.skill.code||r.skill.id):''}</td>`+
                  `<td style="display:none;" class="col-order">${r.sortOrder??''}</td>`+
                  `<td>${del}${up}${down}${edit}</td></tr>`;
      }
      html += `</tbody></table>`;
      el.innerHTML = html;
      // enhance: add copy buttons and drag & drop reorder (with left drag handle)
      const tbodyEnh = el.querySelector('tbody');
      if (tbodyEnh && el.id === 'all') { // D&Dは「すべて表示」テーブルのみ対応
        tbodyEnh.querySelectorAll('tr').forEach(tr => {
          const idVal = Number(tr.getAttribute('data-id'));
          // insert drag handle cell just after hidden id cell
          const idCell = tr.querySelector('td.col-id');
          if (idCell && !tr.querySelector('button.drag-handle')) {
            const handleCell = document.createElement('td');
            const handleBtn = document.createElement('button');
            handleBtn.type = 'button';
            handleBtn.className = 'drag-handle';
            handleBtn.textContent = '≡';
            handleBtn.style.cssText = 'width:24px;cursor:grab;background:transparent;border:0;color:#94a3b8;font-size:16px;';
            handleCell.appendChild(handleBtn);
            idCell.parentNode.insertBefore(handleCell, idCell.nextSibling);
            // Only allow drag when using the handle
            handleBtn.addEventListener('mousedown', () => { tr.setAttribute('draggable','true'); });
            handleBtn.addEventListener('mouseup', () => { tr.removeAttribute('draggable'); });
            handleBtn.addEventListener('mouseleave', () => { tr.removeAttribute('draggable'); });
          }
          // add copy button
          const actionTd = tr.querySelector('td:last-child');
          if (actionTd && !actionTd.querySelector('.btn-copy')) {
            const btn = document.createElement('button');
            btn.className = 'btn-copy';
            btn.textContent = 'コピー';
            btn.style.marginLeft = '6px';
            btn.addEventListener('click', async () => {
              try{
                const res = await fetch(`/api/demand/${idVal}/copy`, { method:'POST', headers:{'Content-Type':'application/json'}, body: '{}' });
                if(!res.ok){
                  try{ const j = await res.json(); alert(j.message||'コピーに失敗しました'); }catch(_){ alert('コピーに失敗しました'); }
                  return;
                }
                if(document.getElementById('date').value){ await loadEffective(); }
                await loadAll();
              }catch(_){ alert('コピーに失敗しました'); }
            });
            actionTd.appendChild(btn);
          }
          // row drag handlers
          tr.addEventListener('dragstart', (ev) => {
            tr.classList.add('dragging');
            if (ev.dataTransfer) { ev.dataTransfer.effectAllowed = 'move'; ev.dataTransfer.setData('text/plain', String(idVal)); }
          });
          tr.addEventListener('dragend', () => tr.classList.remove('dragging'));
        });
        tbodyEnh.addEventListener('dragover', (ev) => {
          ev.preventDefault();
          const dragging = tbodyEnh.querySelector('tr.dragging');
          const after = Array.from(tbodyEnh.querySelectorAll('tr:not(.dragging)')).find(row => {
            const rect = row.getBoundingClientRect();
            return ev.clientY <= rect.top + rect.height/2;
          });
          if (!dragging) return;
          if (after) tbodyEnh.insertBefore(dragging, after); else tbodyEnh.appendChild(dragging);
        });
        tbodyEnh.addEventListener('drop', async (ev) => {
          ev.preventDefault();
          const ids = Array.from(tbodyEnh.querySelectorAll('tr'))
            .map(r=>Number(r.getAttribute('data-id')))
            .filter(n => Number.isFinite(n) && n > 0);
          if (ids.length === 0) return;
          try{
            const res = await fetch('/api/demand/reorder', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(ids)});
            if(!res.ok){
              try{ const j = await res.json(); alert(j.message||'並び替えの保存に失敗しました'); }catch(_){ alert('並び替えの保存に失敗しました'); }
            }
          }catch(_){ alert('並び替えの保存に失敗しました'); }
        });
      }
      // wire events
      el.querySelectorAll('.btn-del').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          const id = e.currentTarget.getAttribute('data-id');
          if(!confirm(`ID=${id} を削除しますか？`)) return;
          const res = await fetch(`/api/demand/${id}`, { method:'DELETE' });
          if(!res.ok){ alert('削除に失敗しました'); return; }
          if(document.getElementById('date').value){ await loadEffective(); }
          await loadAll();
        });
      });
      el.querySelectorAll('.btn-move').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          const id = Number(e.currentTarget.getAttribute('data-id'));
          const dir = e.currentTarget.getAttribute('data-dir');
          // find current list order
          const rowsArr = Array.from(el.querySelectorAll('tbody tr'));
          const idx = rowsArr.findIndex(tr => Number(tr.getAttribute('data-id')) === id);
          if (idx === -1) return;
          const swapIdx = dir === 'up' ? idx - 1 : idx + 1;
          if (swapIdx < 0 || swapIdx >= rowsArr.length) return; // no-op
          const otherId = Number(rowsArr[swapIdx].getAttribute('data-id'));
          const res = await fetch(`/api/demand/swap?a=${id}&b=${otherId}`, { method:'POST' });
          if(!res.ok){ alert('並び替えに失敗しました'); return; }
          if(document.getElementById('date').value){ await loadEffective(); }
          await loadAll();
        });
      });
      el.querySelectorAll('.btn-edit').forEach(btn => {
        btn.addEventListener('click', (e) => {
          const id = Number(e.currentTarget.getAttribute('data-id'));
          const row = rows.find(x => x.id === id);
          if(!row) return;
          startEdit(row);
        });
      });
    }
    function startEdit(r){
      editingId = r.id;
      document.getElementById('c_date').value = r.date || '';
      document.getElementById('c_dow').value = r.dayOfWeek || '';
      document.getElementById('c_start').value = (r.startTime||'').slice(0,5);
      document.getElementById('c_end').value = (r.endTime||'').slice(0,5);
      document.getElementById('c_req').value = r.requiredSeats;
      const sel = document.getElementById('c_skill_select');
      sel.value = r.skill ? String(r.skill.id) : '';
      document.getElementById('c_submit').textContent = '更新';
      document.getElementById('c_cancel').style.display = '';
      document.getElementById('create_msg').textContent = '編集モードです。更新後に「更新」を押してください。';
    }
    function cancelEdit(){
      editingId = null;
      document.getElementById('c_date').value = '';
      document.getElementById('c_dow').value = '';
      document.getElementById('c_start').value = '09:00';
      document.getElementById('c_end').value = '18:00';
      document.getElementById('c_req').value = 5;
      document.getElementById('c_skill_select').value = '';
      document.getElementById('c_submit').textContent = '追加';
      document.getElementById('c_cancel').style.display = 'none';
      document.getElementById('create_msg').textContent = '';
    }
    async function createOrUpdate(){
      const body = {
        date: document.getElementById('c_date').value || null,
        dayOfWeek: (document.getElementById('c_dow').value || null),
        startTime: document.getElementById('c_start').value,
        endTime: document.getElementById('c_end').value,
        requiredSeats: Number(document.getElementById('c_req').value),
        skillId: (document.getElementById('c_skill_select').value||'')? Number(document.getElementById('c_skill_select').value): null,
        active: true
      };
      let url = '/api/demand';
      let method = 'POST';
      if (editingId) { url = `/api/demand/${editingId}`; method = 'PUT'; }
      const res = await fetch(url, { method, headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
      const j = await res.json();
      document.getElementById('create_msg').textContent = j.message || (j.success? (editingId? '更新しました':'作成しました'):'失敗しました');
      if(j.success){ cancelEdit(); await loadAll(); if(document.getElementById('date').value){ await loadEffective(); } }
    }
    async function loadSkills(){
      try{
        const res = await fetch('/api/skills');
        const j = await res.json();
        const list = j.data || [];
        const sel = document.getElementById('c_skill_select');
        sel.innerHTML = '<option value="">未指定</option>' + list.map(s => `<option value="${s.id}">${s.name||s.code||s.id}</option>`).join('');
      }catch(e){ /* noop */ }
    }
    window.addEventListener('DOMContentLoaded', async ()=>{
      const today = new Date().toISOString().slice(0,10);
      document.getElementById('date').value = today;
      await loadSkills();
      await loadAll();
    });
  
