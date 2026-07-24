import type { ReactNode } from "react";
import { useCallback } from "react";
import {
  Route as WouterRoute,
  Router as WouterRouter,
  Switch,
  useLocation as useWouterLocation,
} from "wouter";

export interface NavigateOptions {
  replace?: boolean;
  state?: unknown;
}

export interface BrowserRouterProps {
  children: ReactNode;
}

export interface RouteProps {
  path: string;
  element: ReactNode;
}

export function BrowserRouter({ children }: BrowserRouterProps) {
  return <WouterRouter>{children}</WouterRouter>;
}

export function Routes({ children }: BrowserRouterProps) {
  return <Switch>{children}</Switch>;
}

export function Route({ path, element }: RouteProps) {
  return <WouterRoute path={path}>{element}</WouterRoute>;
}

export function useNavigate() {
  const [, setLocation] = useWouterLocation();

  return useCallback(
    (path: string | number, options?: NavigateOptions) => {
      if (typeof path === "number") {
        window.history.go(path);
        return;
      }

      setLocation(path, {
        replace: options?.replace,
        state: options?.state,
      });
    },
    [setLocation],
  );
}

export function useLocation() {
  const [pathname] = useWouterLocation();

  return {
    pathname,
    search: window.location.search,
    hash: window.location.hash,
    state: window.history.state,
  };
}

export function useHref(path: string) {
  return path;
}
