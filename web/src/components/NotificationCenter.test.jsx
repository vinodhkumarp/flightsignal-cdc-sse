import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { NotificationCenter } from './NotificationCenter.jsx';

const events = [
  {
    eventId: '2',
    occurredAt: '2026-10-02T01:00:00Z',
    type: 'flight.departure.delayed',
    severity: 'warning',
    message: 'Departure for QF11 has been delayed by 25 minutes.',
    flight: { carrierCode: 'QF', flightNumber: '11' }
  },
  {
    eventId: '1',
    occurredAt: '2026-10-02T00:00:00Z',
    type: 'flight.added',
    severity: 'info',
    message: 'Flight QF11 from SYD to LAX has been added.',
    flight: { carrierCode: 'QF', flightNumber: '11' }
  }
];

function renderCenter(props = {}) {
  const handlers = {
    onClose: vi.fn(),
    onSelect: vi.fn(),
    onLoadOlder: vi.fn(),
    onMarkAllRead: vi.fn()
  };
  render(
    <NotificationCenter
      open
      events={events}
      readEventIds={new Set(['1'])}
      hasMore={false}
      loadingOlder={false}
      {...handlers}
      {...props}
    />
  );
  return handlers;
}

describe('NotificationCenter', () => {
  it('highlights only unread notifications', () => {
    renderCenter();

    expect(screen.getAllByText('New')).toHaveLength(1);
    expect(screen.getByText(/delayed by 25 minutes/).closest('button'))
      .toHaveClass('notification-item--unread');
  });

  it('focuses the close button and closes on Escape', async () => {
    const user = userEvent.setup();
    const { onClose } = renderCenter();

    expect(screen.getByRole('button', { name: 'Close notification center' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('selects a notification and marks all as read', async () => {
    const user = userEvent.setup();
    const { onSelect, onMarkAllRead } = renderCenter();

    await user.click(screen.getByText(/has been added/));
    expect(onSelect).toHaveBeenCalledWith(events[1]);

    await user.click(screen.getByRole('button', { name: 'Mark all as read' }));
    expect(onMarkAllRead).toHaveBeenCalled();
  });

  it('renders nothing when closed', () => {
    const { container } = render(
      <NotificationCenter open={false} events={events} readEventIds={new Set()} />
    );
    expect(container).toBeEmptyDOMElement();
  });
});
