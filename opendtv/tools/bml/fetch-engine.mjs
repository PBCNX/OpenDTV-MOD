/*
 * fetch-engine.mjs - put the pinned web-bml engine where the build expects it.
 *
 *   node fetch-engine.mjs [--force]
 *
 * Reads engine.lock.json, clones the public repository AT THE LOCKED COMMIT (not the tag: a tag can
 * be moved, a commit cannot), verifies the commit it got, and copies dist/ into vendor/web-bml.
 * The checkout is temporary and the copy is gitignored - what lives in git is the pin, not the code.
 *
 * Why a fetch instead of a vendored copy: 2.6 MB of someone else's dist does not belong in this
 * repository, and a stranger rebuilding the public source should get the identical engine from the
 * same place we did.
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, rmSync, cpSync, mkdirSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const here = dirname(fileURLToPath(import.meta.url));
const lock = JSON.parse(readFileSync(join(here, 'engine.lock.json'), 'utf8'));
const target = join(here, lock.into);
const force = process.argv.includes('--force');

function git(args, cwd) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

if (!force) {
  try {
    if (readdirSync(target).length > 0) {
      console.log(`engine already at ${lock.into} (use --force to refetch)`);
      process.exit(0);
    }
  } catch { /* not there yet */ }
}

rmSync(target, { recursive: true, force: true });
const work = join(tmpdir(), `web-bml-${process.pid}`);
rmSync(work, { recursive: true, force: true });

try {
  console.log(`fetching ${lock.repo} ${lock.tag} (must be ${lock.commit.slice(0, 7)})`);
  // Clone by tag, then VERIFY the commit: a fetch by SHA is refused by some remotes, and the check
  // below is what makes the pin real - if the tag has moved, this stops.
  git(['clone', '--quiet', '--depth', '1', '--branch', lock.tag, lock.repo, work]);

  const got = git(['rev-parse', 'HEAD'], work);
  if (got !== lock.commit) {
    throw new Error(`expected ${lock.commit}, got ${got} - the tag moved; update engine.lock.json deliberately`);
  }

  // dist/ is the engine; fonts/ are the faces it expects the HOST to register (M PLUS 1 Code and
  // 1P for the profile's look, Kosugi as the last resort). Copying only dist/ was the bug that made
  // a 2.0.0 build still render in Kosugi: the host had nothing else to offer.
  const from = join(work, lock.dist);
  mkdirSync(target, { recursive: true });
  // dist: "." copies the whole tree (2.0.2 has no dist/); skip its .git, which has no business in a
// gitignored vendor directory and confuses tools that walk up looking for a repository.
cpSync(from, target, { recursive: true, filter: (p) => !/(^|[\\/])\.git([\\/]|$)/.test(p) });
const faces = join(work, 'fonts');
  try { cpSync(faces, join(target, 'fonts'), { recursive: true }); } catch { /* older tags have none */ }
  writeFileSync(join(target, 'ENGINE-PIN.txt'),
    `web-bml ${lock.tag}\n${lock.repo}\n${lock.commit}\nfetched into vendor/web-bml (gitignored)\n`, 'utf8');
  console.log(`engine ${lock.tag} @ ${lock.commit.slice(0, 7)} -> ${lock.into}`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
