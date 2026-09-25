// Security-rules tests for the groups feature. Run with `npm test` (starts the Firestore
// emulator, runs this file against it). CI does the same on every push.
import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc, writeBatch, increment } from 'firebase/firestore';

const PROJECT = 'khatwa-de941';
const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080').split(':');
let env;

// The real admin key is never in the repository; tests swap its hash for the hash of this one.
export const TEST_ADMIN_KEY = 'KHATWATESTADMINKEY22';
const withTestAdminKey = (rules) => {
  const hash = createHash('sha256').update(TEST_ADMIN_KEY).digest('hex');
  const out = rules.replace(/(function adminKeySha256\(\) \{ return ')[0-9a-f]{64}(')/, `$1${hash}$2`);
  assert.notEqual(out, rules, 'adminKeySha256() not found in firestore.rules');
  return out;
};

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: withTestAdminKey(readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8')), host, port: Number(port) },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const db = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

const stats = (over = {}) => ({
  streak: 3,
  today: { key: '2026-09-24', steps: 4000, goalDays: 0, longestMs: 600000 },
  week: { key: '2026-09-20', steps: 20000, goalDays: 2, longestMs: 900000 },
  month: { key: '2026-09-01', steps: 90000, goalDays: 10, longestMs: 900000 },
  updatedAt: 1,
  ...over,
});
const totals = () => ({ steps: 100, members: 1, goalMet: 0, goalRatio: 0 });
const summary = () => ({ date: '2026-09-24', today: totals(), week: totals(), month: totals(), updatedAt: 1 });

/** Seeds a profile without rules (what the app does right after anonymous sign-in, validated separately). */
async function seedUser(uid, nickname, ownedGroups = 0) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'users', uid), { nickname, ownedGroups, createdAt: 1 });
  });
}

/** The create-group batch exactly as the app writes it. */
async function createGroup(uid, gid, code, over = {}) {
  const d = db(uid);
  const b = writeBatch(d);
  b.set(doc(d, 'groups', gid), { name: 'Walkers', description: 'desc', ownerUid: uid, memberCount: 1, hidden: false, createdAt: 1, summary: summary(), ...over });
  b.set(doc(d, 'groups', gid, 'private', 'invite'), { code });
  b.set(doc(d, 'groups', gid, 'members', uid), { nickname: 'Owner', joinedAt: 1 });
  b.set(doc(d, 'invites', code), { groupId: gid });
  b.set(doc(d, 'users', uid, 'memberships', gid), { joinedAt: 1 });
  b.update(doc(d, 'users', uid), { ownedGroups: increment(1) });
  return b.commit();
}

/** The join batch: membership with the code + memberCount + 1. */
async function join(uid, gid, code, nickname = 'Joiner') {
  const d = db(uid);
  const b = writeBatch(d);
  b.set(doc(d, 'groups', gid, 'members', uid), { nickname, joinedAt: 2, code });
  b.update(doc(d, 'groups', gid), { memberCount: increment(1) });
  b.set(doc(d, 'users', uid, 'memberships', gid), { joinedAt: 2 });
  return b.commit();
}

const CODE = 'ABCD2345';

test('profile: only the owner reads/writes it, nickname bounded, ownedGroups starts at 0', async () => {
  await assertSucceeds(setDoc(doc(db('u1'), 'users', 'u1'), { nickname: 'Ahmad', ownedGroups: 0, createdAt: 1 }));
  await assertFails(setDoc(doc(db('u1'), 'users', 'u1'), { nickname: '', ownedGroups: 0, createdAt: 1 }));
  await assertFails(setDoc(doc(db('u2'), 'users', 'u1'), { nickname: 'X', ownedGroups: 0, createdAt: 1 }));
  await assertFails(getDoc(doc(db('u2'), 'users', 'u1')));
  await assertFails(setDoc(doc(anon(), 'users', 'a'), { nickname: 'A', ownedGroups: 0, createdAt: 1 }));
  // Own membership list: private to the user.
  await assertSucceeds(setDoc(doc(db('u1'), 'users', 'u1', 'memberships', 'g1'), { joinedAt: 1 }));
  await assertSucceeds(getDocs(collection(db('u1'), 'users', 'u1', 'memberships')));
  await assertFails(getDocs(collection(db('u2'), 'users', 'u1', 'memberships')));
  await assertFails(setDoc(doc(db('u2'), 'users', 'u1', 'memberships', 'g2'), { joinedAt: 1 }));
});

test('create group: owner batch succeeds; bad shapes and a 6th group fail', async () => {
  await seedUser('u1', 'Ahmad');
  await assertSucceeds(createGroup('u1', 'g1', CODE));
  await assertFails(createGroup('u1', 'g2', 'bad code'));
  await assertFails(createGroup('u1', 'g3', 'BCDE2345', { name: '' }));
  await assertFails(createGroup('u1', 'g4', 'CDEF2345', { memberCount: 2 }));
  await seedUser('u5', 'Full', 5);
  await assertFails(createGroup('u5', 'g5', 'DEFG2345'));
});

test('non-members see the card and anonymous numbers, never members or the invite code', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  await assertSucceeds(setDoc(doc(db('u1'), 'groups', 'g1', 'contrib', 'u1'), stats()));
  const stranger = db('u9');
  await assertSucceeds(getDoc(doc(stranger, 'groups', 'g1')));
  await assertSucceeds(getDocs(collection(stranger, 'groups', 'g1', 'contrib')));
  await assertFails(getDocs(collection(stranger, 'groups', 'g1', 'members')));
  await assertFails(getDoc(doc(stranger, 'groups', 'g1', 'private', 'invite')));
  await assertFails(getDocs(collection(stranger, 'invites')));
  await assertSucceeds(getDoc(doc(stranger, 'invites', CODE)));
  await assertFails(getDoc(doc(anon(), 'groups', 'g1')));
});

test('join: correct code succeeds, wrong code or missing counter fails, member sees members', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  await assertFails(join('u2', 'g1', 'WRONG234'));
  await assertFails(setDoc(doc(db('u2'), 'groups', 'g1', 'members', 'u2'), { nickname: 'J', joinedAt: 2, code: CODE }));
  await assertSucceeds(join('u2', 'g1', CODE));
  await assertSucceeds(getDocs(collection(db('u2'), 'groups', 'g1', 'members')));
  await assertSucceeds(getDoc(doc(db('u2'), 'groups', 'g1', 'private', 'invite')));
  const card = await getDoc(doc(db('u2'), 'groups', 'g1'));
  assert.equal(card.data().memberCount, 2);
});

test('contrib: only a member writes its own numbers, 60 000 steps/day is the cap', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  await join('u2', 'g1', CODE);
  await assertSucceeds(setDoc(doc(db('u2'), 'groups', 'g1', 'contrib', 'u2'), stats()));
  await assertFails(setDoc(doc(db('u2'), 'groups', 'g1', 'contrib', 'u2'), stats({ today: { key: '2026-09-24', steps: 60001, goalDays: 0, longestMs: 0 } })));
  await assertFails(setDoc(doc(db('u2'), 'groups', 'g1', 'contrib', 'u2'), stats({ extra: 1 })));
  await assertFails(setDoc(doc(db('u2'), 'groups', 'g1', 'contrib', 'u1'), stats()));
  await assertFails(setDoc(doc(db('u9'), 'groups', 'g1', 'contrib', 'u9'), stats()));
});

test('summary: a member refreshes it, a stranger cannot, and nobody edits the counter freely', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  await join('u2', 'g1', CODE);
  await assertSucceeds(updateDoc(doc(db('u2'), 'groups', 'g1'), { summary: summary() }));
  await assertFails(updateDoc(doc(db('u9'), 'groups', 'g1'), { summary: summary() }));
  await assertFails(updateDoc(doc(db('u2'), 'groups', 'g1'), { memberCount: 50 }));
  await assertFails(updateDoc(doc(db('u2'), 'groups', 'g1'), { name: 'Hijacked' }));
  await assertSucceeds(updateDoc(doc(db('u1'), 'groups', 'g1'), { name: 'Renamed', hidden: true }));
});

test('leave and remove: self-leave and owner removal succeed, others fail, owner cannot leave', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  await join('u2', 'g1', CODE);
  await join('u3', 'g1', CODE, 'Third');
  const removal = (actor, victim) => {
    const d = db(actor);
    const b = writeBatch(d);
    b.delete(doc(d, 'groups', 'g1', 'members', victim));
    b.update(doc(d, 'groups', 'g1'), { memberCount: increment(-1) });
    return b.commit();
  };
  await assertFails(removal('u3', 'u2'));   // a member cannot remove another member
  await assertSucceeds(removal('u2', 'u2')); // self-leave
  await assertSucceeds(removal('u1', 'u3')); // owner removes a member
  await assertFails(removal('u1', 'u1'));    // the owner cannot leave its own group
});

test('delete group: owner batch succeeds, member cannot', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  await join('u2', 'g1', CODE);
  const del = (uid) => {
    const d = db(uid);
    const b = writeBatch(d);
    b.delete(doc(d, 'groups', 'g1', 'members', 'u1'));
    b.delete(doc(d, 'groups', 'g1', 'members', 'u2'));
    b.delete(doc(d, 'groups', 'g1', 'private', 'invite'));
    b.delete(doc(d, 'invites', CODE));
    b.delete(doc(d, 'groups', 'g1'));
    b.update(doc(d, 'users', uid), { ownedGroups: increment(-1) });
    return b.commit();
  };
  await assertFails(del('u2'));
  await assertSucceeds(del('u1'));
});

test('regenerate invite code: owner only, old lookup removed', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  const NEW = 'ZYXW9876';
  const regen = (uid) => {
    const d = db(uid);
    const b = writeBatch(d);
    b.set(doc(d, 'groups', 'g1', 'private', 'invite'), { code: NEW });
    b.delete(doc(d, 'invites', CODE));
    b.set(doc(d, 'invites', NEW), { groupId: 'g1' });
    return b.commit();
  };
  await join('u2', 'g1', CODE);
  await assertFails(regen('u2'));
  await assertSucceeds(regen('u1'));
  await assertFails(join('u3', 'g1', CODE));
  await assertSucceeds(join('u3', 'g1', NEW));
});

test('admin key: the wrong key, a key for someone else, or a changed claim all fail', async () => {
  await assertFails(setDoc(doc(db('u1'), 'admins', 'u1'), { key: 'WRONGKEY', claimedAt: 1 }));
  await assertFails(setDoc(doc(db('u1'), 'admins', 'u2'), { key: TEST_ADMIN_KEY, claimedAt: 1 }));
  await assertFails(setDoc(doc(db('u1'), 'admins', 'u1'), { key: TEST_ADMIN_KEY, claimedAt: 1, extra: true }));
  await assertFails(setDoc(doc(anon(), 'admins', 'x'), { key: TEST_ADMIN_KEY, claimedAt: 1 }));
  await assertSucceeds(setDoc(doc(db('u1'), 'admins', 'u1'), { key: TEST_ADMIN_KEY, claimedAt: 1 }));
  await assertSucceeds(getDoc(doc(db('u1'), 'admins', 'u1')));
  await assertFails(getDoc(doc(db('u2'), 'admins', 'u1')));
  await assertFails(getDocs(collection(db('u2'), 'admins')));
  // A normal user is still a normal user: no access to others' profiles.
  await seedUser('u3', 'Other');
  await assertFails(getDoc(doc(db('u2'), 'users', 'u3')));
});

test('admin: reads and changes everything, including hidden groups and other users', async () => {
  await seedUser('u1', 'Owner');
  await seedUser('u2', 'Member');
  await createGroup('u1', 'g1', CODE, { hidden: true });
  await join('u2', 'g1', CODE);
  await assertSucceeds(setDoc(doc(db('adm'), 'admins', 'adm'), { key: TEST_ADMIN_KEY, claimedAt: 1 }));
  const a = db('adm');
  await assertSucceeds(getDocs(collection(a, 'users')));
  await assertSucceeds(getDocs(collection(a, 'groups')));
  await assertSucceeds(getDocs(collection(a, 'groups', 'g1', 'members')));
  await assertSucceeds(getDoc(doc(a, 'groups', 'g1', 'private', 'invite')));
  await assertSucceeds(getDocs(collection(a, 'users', 'u2', 'memberships')));
  await assertSucceeds(updateDoc(doc(a, 'groups', 'g1'), { name: 'Renamed by admin', hidden: false }));
  await assertSucceeds(updateDoc(doc(a, 'users', 'u2'), { nickname: 'Renamed' }));
  // Remove a member everywhere (their own membership list included), then delete the group.
  const rm = writeBatch(a);
  rm.delete(doc(a, 'groups', 'g1', 'members', 'u2'));
  rm.delete(doc(a, 'users', 'u2', 'memberships', 'g1'));
  rm.update(doc(a, 'groups', 'g1'), { memberCount: increment(-1) });
  await assertSucceeds(rm.commit());
  const del = writeBatch(a);
  del.delete(doc(a, 'groups', 'g1', 'members', 'u1'));
  del.delete(doc(a, 'users', 'u1', 'memberships', 'g1'));
  del.delete(doc(a, 'groups', 'g1', 'private', 'invite'));
  del.delete(doc(a, 'invites', CODE));
  del.delete(doc(a, 'groups', 'g1'));
  del.update(doc(a, 'users', 'u1'), { ownedGroups: increment(-1) });
  await assertSucceeds(del.commit());
});

test('backup: only the owner reads and writes it, not other users and not the admin', async () => {
  const data = 'H4sIAAAAAAAA/6tWKkktLlGyUlAqzy/KSVGqBQCnU7xLEgAAAA==';
  await assertSucceeds(setDoc(doc(db('u1'), 'users', 'u1', 'backup', 'latest'), { data, updatedAt: 1, bytes: 40, version: 1 }));
  await assertSucceeds(getDoc(doc(db('u1'), 'users', 'u1', 'backup', 'latest')));
  await assertFails(getDoc(doc(db('u2'), 'users', 'u1', 'backup', 'latest')));
  await assertFails(setDoc(doc(db('u2'), 'users', 'u1', 'backup', 'latest'), { data, updatedAt: 2, bytes: 40, version: 1 }));
  await assertFails(setDoc(doc(db('u1'), 'users', 'u1', 'backup', 'latest'), { data, updatedAt: 1, bytes: 40, version: 1, extra: 1 }));
  await assertFails(setDoc(doc(db('u1'), 'users', 'u1', 'backup', 'latest'), { data: 'x'.repeat(1000001), updatedAt: 1, bytes: 1, version: 1 }));
  await assertSucceeds(setDoc(doc(db('adm'), 'admins', 'adm'), { key: TEST_ADMIN_KEY, claimedAt: 1 }));
  await assertFails(getDoc(doc(db('adm'), 'users', 'u1', 'backup', 'latest')));
});

test('admin password: the admin sets a new one; then the one-time key stops working', async () => {
  const sha = (t) => createHash('sha256').update(t).digest('hex');
  await assertSucceeds(setDoc(doc(db('adm'), 'admins', 'adm'), { key: TEST_ADMIN_KEY, claimedAt: 1 }));
  // Nobody else may set the password.
  await assertFails(setDoc(doc(db('u1'), 'config', 'admin'), { keySha256: sha('mine'), changedAt: 1 }));
  await assertSucceeds(setDoc(doc(db('adm'), 'config', 'admin'), { keySha256: sha('My own Secret 9!'), changedAt: 1 }));
  await assertFails(getDoc(doc(db('u1'), 'config', 'admin')));
  await assertFails(setDoc(doc(db('u2'), 'admins', 'u2'), { key: TEST_ADMIN_KEY, claimedAt: 1 }));
  await assertFails(setDoc(doc(db('u2'), 'admins', 'u2'), { key: 'my own secret 9!', claimedAt: 1 }));
  await assertSucceeds(setDoc(doc(db('u2'), 'admins', 'u2'), { key: 'My own Secret 9!', claimedAt: 1 }));
});

test('group days: members write the day totals, strangers only read them', async () => {
  await seedUser('u1', 'Ahmad');
  await createGroup('u1', 'g1', CODE);
  const day = { steps: 12000, members: 1, goalMet: 1, goalRatio: 1 };
  await assertSucceeds(setDoc(doc(db('u1'), 'groups', 'g1', 'days', '2026-09-25'), day));
  await assertFails(setDoc(doc(db('u9'), 'groups', 'g1', 'days', '2026-09-25'), day));
  await assertFails(setDoc(doc(db('u1'), 'groups', 'g1', 'days', '2026-09-25'), { ...day, extra: 1 }));
  await assertFails(setDoc(doc(db('u1'), 'groups', 'g1', 'days', 'yesterday'), day));
  await assertSucceeds(getDoc(doc(db('u9'), 'groups', 'g1', 'days', '2026-09-25')));
});
