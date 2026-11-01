import { useNavigate } from 'react-router';
import { useAuth } from '../auth/useAuth';

export function DemoControls() {
    const { state, demo, demoMessage, pending, transitionDemo } = useAuth();
    const navigate = useNavigate();
    if (!demo?.enabled || (state.status !== 'authenticated' && !pending)) return null;
    const role = state.status === 'authenticated' ? state.user.role : undefined;
    return <section className="demo-controls" aria-label="Synthetic demo" aria-busy={pending}>
        <div>
            <h2>Synthetic demo{role ? ` · ${role === 'REQUESTER' ? 'Requester' : 'Agent'}` : ''}</h2>
            <p>Use invented information only. {demo.expiresAt && <>Expires <time dateTime={demo.expiresAt}>{new Date(demo.expiresAt).toLocaleString()}</time>. </>}30-minute absolute lifetime.</p>
        </div>
        <div className="demo-actions" role="group" aria-label="Demo role">
            {(['requester', 'agent'] as const).map((action) => <button key={action} type="button" className="secondary-button"
                disabled={pending || role === action.toUpperCase()} aria-pressed={role === action.toUpperCase()}
                onClick={() => {
                    navigate('/app/tickets', { replace: true });
                    void transitionDemo(action);
                }}>Switch to {action}</button>)}
        </div>
        {pending && <p role="status">Updating demo session…</p>}
        {demoMessage && <p role="alert" className="error-message">{demoMessage}</p>}
    </section>;
}
