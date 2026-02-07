import { Link, useLocation } from 'react-router-dom';

export function Header() {
  const location = useLocation();
  const isEditor = location.pathname.startsWith('/flows/') && location.pathname !== '/flows';

  return (
    <header
      style={{
        height: 48,
        background: 'var(--color-surface)',
        borderBottom: '1px solid var(--color-border)',
        display: 'flex',
        alignItems: 'center',
        padding: '0 16px',
        gap: 16,
        flexShrink: 0,
      }}
    >
      <Link to="/flows" style={{ fontWeight: 700, fontSize: 16 }}>
        atadflow
      </Link>
      {isEditor && (
        <Link to="/flows" style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>
          All Flows
        </Link>
      )}
    </header>
  );
}
