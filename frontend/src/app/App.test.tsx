import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('application routes', () => {
    it('renders the Issunexa foundation in semantic main content at the root', () => {
        render(<MemoryRouter initialEntries={['/']}><App /></MemoryRouter>);

        const main = screen.getByRole('main');
        expect(within(main).getByRole('heading', { level: 1, name: 'Issunexa' })).toBeVisible();
        expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
        expect(screen.getByRole('banner')).toBeVisible();
    });

    it('renders a not-found page for an unknown route', () => {
        render(<MemoryRouter initialEntries={['/missing/page']}><App /></MemoryRouter>);

        expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
        expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    });

    it('lets a keyboard user return home from an unknown route', async () => {
        const user = userEvent.setup();
        render(<MemoryRouter initialEntries={['/missing']}><App /></MemoryRouter>);

        await user.tab();
        expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveFocus();
        await user.tab();
        await user.tab();
        expect(screen.getByRole('link', { name: 'Return to home' })).toHaveFocus();
        await user.keyboard('{Enter}');

        expect(screen.getByRole('heading', { level: 1, name: 'Issunexa' })).toBeVisible();
        expect(screen.queryByRole('heading', { name: 'Page not found' })).not.toBeInTheDocument();
    });
});
