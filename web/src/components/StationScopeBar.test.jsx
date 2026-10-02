import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { StationScopeBar } from './StationScopeBar.jsx';

const agent = { name: 'Agent', allStations: false, stations: ['SIN', 'SYD'] };

describe('StationScopeBar', () => {
  it('offers only the stations from the user token', () => {
    render(<StationScopeBar user={agent} selected={[]} onChange={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'SIN' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'SYD' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AKL' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'All mine' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('toggles stations in and out of the filter', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<StationScopeBar user={agent} selected={['SYD']} onChange={onChange} />);

    await user.click(screen.getByRole('button', { name: 'SIN' }));
    expect(onChange).toHaveBeenLastCalledWith(['SIN', 'SYD']);

    await user.click(screen.getByRole('button', { name: 'SYD' }));
    expect(onChange).toHaveBeenLastCalledWith([]);
  });

  it('explains when the user has no stations', () => {
    render(<StationScopeBar user={{ ...agent, stations: [] }} selected={[]} onChange={vi.fn()} />);

    expect(screen.getByRole('status')).toHaveTextContent('no stations assigned');
  });

  it('lets head office pick any station', () => {
    render(<StationScopeBar user={{ name: 'HQ', allStations: true, stations: [] }} selected={[]} onChange={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'AKL' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'All stations' })).toBeInTheDocument();
  });
});
