document.addEventListener('DOMContentLoaded', () => {
    const tableBody = document.querySelector('#employee-table tbody');
    const status = document.getElementById('employee-status');
    const countLabel = document.getElementById('employee-count');
    const form = document.getElementById('employee-form');

    async function fetchEmployees() {
        setStatus('従業員一覧を取得しています...', false);
        try {
            const response = await fetch('/api/employees');
            if (!response.ok) {
                throw new Error('従業員一覧の取得に失敗しました');
            }
            const employees = await response.json();
            renderEmployees(employees);
            countLabel.textContent = `登録人数: ${employees.length} 名`;
            setStatus('従業員一覧を更新しました。', true);
        } catch (error) {
            renderPlaceholder(error.message);
            setStatus(error.message, false, true);
        }
    }

    function renderPlaceholder(message) {
        tableBody.innerHTML = `<tr><td colspan="4" class="placeholder">${message}</td></tr>`;
    }

    function renderEmployees(employees) {
        if (employees.length === 0) {
            renderPlaceholder('登録されている従業員がいません。右側のフォームから追加してください。');
            return;
        }

        tableBody.innerHTML = employees.map(employee => `
            <tr>
                <td>${employee.id}</td>
                <td>${employee.name}</td>
                <td>${employee.role}</td>
                <td><button class="button button-secondary" data-id="${employee.id}">削除</button></td>
            </tr>
        `).join('');
    }

    function setStatus(message, success = false, isError = false) {
        status.textContent = message;
        status.classList.remove('success', 'error');
        if (success) {
            status.classList.add('success');
        }
        if (isError) {
            status.classList.add('error');
        }
    }

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const formData = new FormData(form);
        const name = formData.get('name').trim();
        const role = formData.get('role').trim();

        if (!name || !role) {
            setStatus('氏名と役割を入力してください。', false, true);
            return;
        }

        setStatus('登録処理を実行しています...', false);
        try {
            const response = await fetch('/api/employees', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ name, role })
            });

            if (!response.ok) {
                throw new Error('従業員の登録に失敗しました。入力内容を確認してください。');
            }

            form.reset();
            setStatus('従業員を登録しました。', true);
            await fetchEmployees();
        } catch (error) {
            setStatus(error.message, false, true);
        }
    });

    tableBody.addEventListener('click', async (event) => {
        const target = event.target;
        if (target instanceof HTMLButtonElement && target.dataset.id) {
            const id = target.dataset.id;
            if (!confirm('選択した従業員を削除しますか？')) {
                return;
            }

            setStatus('削除処理を実行しています...', false);
            try {
                const response = await fetch(`/api/employees/${id}`, {
                    method: 'DELETE'
                });
                if (!response.ok && response.status !== 204) {
                    throw new Error('従業員の削除に失敗しました');
                }
                setStatus('従業員を削除しました。', true);
                await fetchEmployees();
            } catch (error) {
                setStatus(error.message, false, true);
            }
        }
    });

    fetchEmployees();
});
