import { useState } from 'react';
import { useAuth } from './auth/AuthContext.jsx';
import { DevSignIn, Splash, SsoSignIn } from './components/SignIn.jsx';
import { Workspace } from './components/Workspace.jsx';

export default function App() {
  const auth = useAuth();
  const [stationFilter, setStationFilter] = useState([]);

  if (auth.status === 'loading') {
    return <Splash message="Signing in…" />;
  }

  if (auth.status === 'error') {
    return <Splash message={auth.error} onRetry={auth.retry} />;
  }

  if (auth.status === 'signedOut') {
    return auth.interactiveSignIn
      ? <DevSignIn onSignIn={auth.signIn} />
      : <SsoSignIn onSignIn={auth.signIn} />;
  }

  async function signOut() {
    setStationFilter([]);
    await auth.signOut();
  }

  return (
    <Workspace
      key={`${auth.user.subject}|${stationFilter.join(',')}`}
      user={auth.user}
      stationFilter={stationFilter}
      onStationFilterChange={setStationFilter}
      onSignOut={signOut}
    />
  );
}
