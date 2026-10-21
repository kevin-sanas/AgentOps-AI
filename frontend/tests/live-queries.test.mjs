import test from 'node:test'
import assert from 'node:assert/strict'
import { scopedResult,pageAfterRefresh } from '../src/presentation.ts'
test('a late response for A cannot label B, or a 24h response label a new 1h range',()=>{const saved={scope:'A/24h',value:[120]};assert.equal(scopedResult(saved,'B/24h'),null);assert.equal(scopedResult(saved,'A/1h'),null);assert.deepEqual(scopedResult(saved,'A/24h'),[120])})
test('processing the final waiting task on page two returns to remaining page one',()=>{assert.equal(pageAfterRefresh(1,20,21),1);assert.equal(pageAfterRefresh(1,20,20),0);assert.equal(pageAfterRefresh(2,20,0),0);assert.equal(pageAfterRefresh(3,20,45),2)})
