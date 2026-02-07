import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { flowsApi } from '@/api/flows';
import type { FlowSummaryDto } from '@/types/flow';

export function FlowListPage() {
  const [flows, setFlows] = useState<FlowSummaryDto[]>([]);
  const [name, setName] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    flowsApi.list().then(setFlows);
  }, []);

  const createFlow = async () => {
    if (!name.trim()) return;
    const flow = await flowsApi.create({ name: name.trim(), description: '' });
    navigate(`/flows/${flow.id}`);
  };

  const deleteFlow = async (id: string) => {
    await flowsApi.delete(id);
    setFlows((f) => f.filter((fl) => fl.id !== id));
  };

  return (
    <div style={{ padding: 24, maxWidth: 700 }}>
      <h2 style={{ marginBottom: 16 }}>Flows</h2>
      <div style={{ display: 'flex', gap: 8, marginBottom: 24 }}>
        <input
          placeholder="New flow name..."
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && createFlow()}
          style={{ maxWidth: 300 }}
        />
        <button className="primary" onClick={createFlow}>
          Create
        </button>
      </div>
      {flows.length === 0 && (
        <p style={{ color: 'var(--color-text-muted)' }}>No flows yet. Create one above.</p>
      )}
      {flows.map((flow) => (
        <div
          key={flow.id}
          style={{
            padding: '12px 16px',
            border: '1px solid var(--color-border)',
            borderRadius: 8,
            marginBottom: 8,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            background: 'var(--color-surface)',
          }}
        >
          <div>
            <a
              onClick={() => navigate(`/flows/${flow.id}`)}
              style={{ cursor: 'pointer', fontWeight: 600 }}
            >
              {flow.name}
            </a>
            {flow.description && (
              <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginTop: 2 }}>
                {flow.description}
              </div>
            )}
          </div>
          <button className="danger" onClick={() => deleteFlow(flow.id)}>
            Delete
          </button>
        </div>
      ))}
    </div>
  );
}
