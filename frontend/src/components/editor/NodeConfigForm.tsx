import type { ConfigFieldDescriptor } from '@/types/node';

interface NodeConfigFormProps {
  configSchema: Record<string, ConfigFieldDescriptor>;
  config: Record<string, unknown>;
  onChange: (key: string, value: unknown) => void;
}

export function NodeConfigForm({ configSchema, config, onChange }: NodeConfigFormProps) {
  return (
    <div className="config-form">
      {Object.entries(configSchema).map(([key, field]) => (
        <div key={key} className="config-field">
          <label>{field.label}</label>
          {field.type === 'select' && field.options ? (
            <select
              value={String(config[key] ?? field.defaultValue ?? '')}
              onChange={(e) => onChange(key, e.target.value)}
            >
              {field.options.map((opt) => (
                <option key={opt} value={opt}>
                  {opt}
                </option>
              ))}
            </select>
          ) : field.type === 'expression' ? (
            <textarea
              rows={2}
              value={String(config[key] ?? field.defaultValue ?? '')}
              onChange={(e) => onChange(key, e.target.value)}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
          ) : (
            <input
              type={field.type === 'number' ? 'number' : 'text'}
              value={String(config[key] ?? field.defaultValue ?? '')}
              onChange={(e) =>
                onChange(key, field.type === 'number' ? Number(e.target.value) : e.target.value)
              }
            />
          )}
        </div>
      ))}
    </div>
  );
}
