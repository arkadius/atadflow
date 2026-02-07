export interface PortDescriptor {
  id: string;
  label: string;
}

export interface ConfigFieldDescriptor {
  label: string;
  type: 'string' | 'select' | 'number' | 'expression' | 'key-value-list';
  defaultValue: unknown;
  options: string[] | null;
}

export interface NodeTypeDescriptor {
  id: string;
  label: string;
  inputs: PortDescriptor[];
  outputs: PortDescriptor[];
  configSchema: Record<string, ConfigFieldDescriptor>;
  defaultCodeTemplate: string;
}
