// Steady background traffic for the demo shop. Runs forever; scale with LOAD_MULTIPLIER.
import http from 'k6/http';
import { sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://app:8080';
const M = Number(__ENV.LOAD_MULTIPLIER || 1);

const CATEGORIES = ['books', 'electronics', 'home', 'fashion', 'outdoor', 'kitchen', 'toys', 'sports'];
const TERMS = ['classic', 'smart', 'eco', 'pro', 'mini', 'ultra', 'vintage', 'urban', 'wireless', 'camping'];
const COUPONS = ['SPRING-10', 'WELCOME-5', 'VIP-15', 'LEGACY:CMP-2023', 'LEGACY:CMP-2024'];
const PAYMENT_METHODS = ['card', 'card', 'card', 'paypal', 'bank_transfer'];

const pick = (a) => a[Math.floor(Math.random() * a.length)];
const randInt = (lo, hi) => lo + Math.floor(Math.random() * (hi - lo + 1));

function rate(name, perSecond, exec) {
  return {
    executor: 'constant-arrival-rate',
    rate: Math.max(1, Math.round(perSecond * M)),
    timeUnit: '1s',
    duration: '8760h',
    preAllocatedVUs: 10,
    maxVUs: 200,
    exec,
    tags: { flow: name },
  };
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    browse: rate('browse', 3, 'browse'),
    detail: rate('detail', 6, 'detail'),
    search: rate('search', 2, 'search'),
    recommend: rate('recommend', 2, 'recommend'),
    checkout: rate('checkout', 3, 'checkout'),
    orderStatus: rate('orderStatus', 1, 'orderStatus'),
  },
};

const params = { timeout: '15s' };

export function browse() {
  http.get(`${BASE}/api/products?category=${pick(CATEGORIES)}`, { ...params, tags: { name: 'GET /api/products' } });
}

export function detail() {
  http.get(`${BASE}/api/products/${randInt(1, 5000)}`, { ...params, tags: { name: 'GET /api/products/{id}' } });
}

export function search() {
  http.get(`${BASE}/api/search?q=${pick(TERMS)}`, { ...params, tags: { name: 'GET /api/search' } });
}

export function recommend() {
  http.get(`${BASE}/api/recommendations?customerId=${randInt(1, 20000)}`, { ...params, tags: { name: 'GET /api/recommendations' } });
}

export function orderStatus() {
  http.get(`${BASE}/api/orders/${randInt(1, 300000)}`, { ...params, tags: { name: 'GET /api/orders/{id}' } });
}

export function checkout() {
  const items = [];
  for (let i = 0; i < randInt(1, 3); i++) {
    items.push({ productId: randInt(1, 5000), quantity: randInt(1, 2) });
  }
  const body = {
    customerId: randInt(1, 20000),
    items,
    couponCode: Math.random() < 0.4 ? pick(COUPONS) : null,
    paymentMethod: pick(PAYMENT_METHODS),
  };
  http.post(`${BASE}/api/orders`, JSON.stringify(body), {
    ...params,
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/orders' },
  });
}

export function setup() {
  // Wait until the app answers before generating load.
  for (let i = 0; i < 120; i++) {
    const res = http.get(`${BASE}/api/categories`, { timeout: '5s' });
    if (res.status === 200) return;
    sleep(2);
  }
}
