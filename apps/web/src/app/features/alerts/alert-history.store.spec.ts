import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../../core/http/interceptors';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import {
  AlertHistoryStore,
  dateRangeError,
  shouldAutoRefresh,
  timeRange,
} from './alert-history.store';
import { rawEvent } from './alert-testing';

const URL_PATH = '/api/v1/alert-events';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  return {
    store: TestBed.inject(AlertHistoryStore),
    http: TestBed.inject(HttpTestingController),
    lifecycle: TestBed.inject(SessionLifecycle),
  };
}

const page = (items: object[], over: object = {}) => ({
  items,
  page: 0,
  size: 25,
  totalItems: items.length,
  totalPages: 1,
  ...over,
});

const request = (http: HttpTestingController) => http.expectOne((req) => req.url === URL_PATH);

describe('time range and auto refresh rules', () => {
  it('turns local days into an inclusive start and an exclusive end', () => {
    const range = timeRange({ from: '2026-05-01', to: '2026-05-03' });
    expect(range.from).toBe(new Date(2026, 4, 1).toISOString());
    // The last day is included: the bound is the start of the day after it.
    expect(range.to).toBe(new Date(2026, 4, 4).toISOString());
  });

  it('leaves out the bounds that are not set', () => {
    expect(timeRange({ from: null, to: null })).toEqual({});
    expect(Object.keys(timeRange({ from: '2026-05-01', to: null }))).toEqual(['from']);
  });

  it('rejects an end before the start, accepts the same day', () => {
    expect(dateRangeError({ from: '2026-05-03', to: '2026-05-01' })).toMatch(/must not be before/);
    expect(dateRangeError({ from: '2026-05-01', to: '2026-05-01' })).toBeNull();
    expect(dateRangeError({ from: null, to: '2026-05-01' })).toBeNull();
  });

  it('refreshes only when something is pending and the tab is visible', () => {
    expect(shouldAutoRefresh(true, true)).toBe(true);
    expect(shouldAutoRefresh(true, false)).toBe(false);
    expect(shouldAutoRefresh(false, true)).toBe(false);
    expect(shouldAutoRefresh(false, false)).toBe(false);
  });
});

describe('AlertHistoryStore', () => {
  it('asks for the first page of 25, newest first as the server sorts it', () => {
    const { store, http } = setup();
    store.load();
    expect(store.status()).toBe('loading');
    const req = request(http);
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.has('status')).toBe(false);
    req.flush(page([rawEvent()], { totalItems: 60, totalPages: 3 }));
    expect(store.status()).toBe('loaded');
    expect(store.totalItems()).toBe(60);
    expect(store.totalPages()).toBe(3);
  });

  it('sends the filters to the server and returns to the first page', () => {
    const { store, http } = setup();
    store.load();
    request(http).flush(page([rawEvent()], { totalPages: 3 }));
    store.setPage(2);
    expect(request(http).request.params.get('page')).toBe('2');

    store.setFilters({
      ruleId: '33333333-3333-4333-8333-333333333333',
      status: 'FAILED',
      from: '2026-05-01',
      to: '2026-05-02',
    });
    const req = request(http);
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('ruleId')).toBe('33333333-3333-4333-8333-333333333333');
    expect(req.request.params.get('status')).toBe('FAILED');
    expect(req.request.params.get('from')).toBe(new Date(2026, 4, 1).toISOString());
    expect(req.request.params.get('to')).toBe(new Date(2026, 4, 3).toISOString());
  });

  it('does not request anything for an impossible date range', () => {
    const { store, http } = setup();
    store.setFilters({ from: '2026-05-03', to: '2026-05-01' });
    http.expectNone(URL_PATH);
    expect(store.rangeError()).not.toBeNull();
  });

  it('clamps the page to the last page', () => {
    const { store, http } = setup();
    store.load();
    request(http).flush(page([rawEvent()], { totalPages: 2 }));
    store.setPage(9);
    expect(request(http).request.params.get('page')).toBe('1');
    store.setPage(-4);
    expect(request(http).request.params.get('page')).toBe('0');
  });

  it('shows only the answer for the filters that are current', () => {
    const { store, http } = setup();
    store.load();
    const slow = request(http);
    store.setFilters({ status: 'FAILED' });
    expect(slow.cancelled).toBe(true);
    request(http).flush(page([rawEvent({ ruleName: 'failed one', deliveryStatus: 'FAILED' })]));
    expect(store.items()[0].ruleName).toBe('failed one');
  });

  it('keeps the rows on screen during a refresh and replaces them with the answer', () => {
    const { store, http } = setup();
    store.load();
    request(http).flush(page([rawEvent({ ruleName: 'before', deliveryStatus: 'PENDING' })]));
    expect(store.hasPending()).toBe(true);

    store.refresh();
    expect(store.status()).toBe('loaded');
    expect(store.refreshing()).toBe(true);
    expect(store.items()[0].ruleName).toBe('before');
    request(http).flush(page([rawEvent({ ruleName: 'after', deliveryStatus: 'SENT' })]));
    expect(store.refreshing()).toBe(false);
    expect(store.items()[0].ruleName).toBe('after');
    expect(store.hasPending()).toBe(false);
  });

  it('keeps the old rows when a background refresh fails', () => {
    const { store, http } = setup();
    store.load();
    request(http).flush(page([rawEvent({ ruleName: 'kept' })]));
    store.refresh();
    request(http).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.status()).toBe('loaded');
    expect(store.items()[0].ruleName).toBe('kept');
    expect(store.refreshing()).toBe(false);
  });

  it('shows an error when the first load fails', () => {
    const { store, http } = setup();
    store.load();
    request(http).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.status()).toBe('error');
    expect(store.problem()?.status).toBe(500);
  });

  it('forgets rows and filters on logout and ignores a late answer', () => {
    const { store, http, lifecycle } = setup();
    store.setFilters({ status: 'SENT' });
    const late = request(http);
    lifecycle.clear();
    late.flush(page([rawEvent()]));
    expect(store.items()).toEqual([]);
    expect(store.status()).toBe('idle');
    expect(store.filters().status).toBeNull();
  });
});
