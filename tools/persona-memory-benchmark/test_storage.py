"""Execute the actual recorded migration and DAO SQL with host SQLite.

Run record_migration.py first. Android Room validation is a separate device gate.
"""
from pathlib import Path
import hashlib
import json
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / 'build/verification/p3-mac/migration/migration-sql.json'


def queries(path):
    text = (ROOT / path).read_text()
    pattern = r'@Query\(("""[\s\S]*?"""|"[^"\n]*")\)\s*(?:abstract\s+)?(?:suspend\s+)?fun\s+(\w+)'
    return {m[2]: m[1][3:-3] if m[1].startswith('"""') else m[1][1:-1]
            for m in re.finditer(pattern, text)}


class StorageTests(unittest.TestCase):
    def setUp(self):
        receipt = json.loads(SQL.with_name('receipt.json').read_text())
        self.assertEqual(receipt['sql_sha256'], hashlib.sha256(SQL.read_bytes()).hexdigest())
        for name, expected in receipt['sources'].items():
            self.assertEqual(expected, hashlib.sha256((ROOT / name).read_bytes()).hexdigest())
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            CREATE TABLE messages(id TEXT PRIMARY KEY, ownerId TEXT, personaId TEXT,
                content TEXT, isFromUser INTEGER, status TEXT, timestamp INTEGER,
                localOnly INTEGER DEFAULT 0, excludeFromHistory INTEGER DEFAULT 0);
            CREATE TABLE memories(id TEXT PRIMARY KEY, ownerId TEXT, personaId TEXT,
                type TEXT, content TEXT, sourceMessageId TEXT, createdAt INTEGER,
                updatedAt INTEGER, expiresAt INTEGER, cloudAllowed INTEGER DEFAULT 0,
                revision INTEGER DEFAULT 1, deleted INTEGER DEFAULT 0);
        ''')
        for statement in json.loads(SQL.read_text()):
            self.db.execute(statement)
        self.q = queries('app/src/main/java/com/example/persona/data/local/dao/MemoryCurationDao.kt')
        self.source('source', 'owner')
        self.source('foreign-source', 'foreign')
        self.candidate('candidate', 'source', 'owner')
        self.candidate('foreign', 'foreign-source', 'foreign')
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def source(self, ident, owner):
        self.db.execute("INSERT INTO messages VALUES (?,?,?,'我喜欢简洁回答',1,'NORMAL',1,0,0)",
                        (ident, owner, 'persona'))

    def candidate(self, ident, source, owner, manual=1, status='PENDING'):
        self.db.execute('INSERT INTO memory_candidates VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',
                        (ident, owner, 'persona', source, 'hash', '我喜欢简洁回答',
                         'PREFERENCE', 'style', 'v1', manual, status, None, 1))

    def memory(self, ident, owner='owner'):
        self.db.execute("INSERT INTO memories(id,ownerId,personaId,type,content,createdAt,updatedAt) VALUES (?,?,'persona','FACT','摘录',1,1)",
                        (ident, owner))

    def state(self, ident):
        return tuple(self.db.execute('SELECT status,content FROM memory_candidates WHERE id=?', (ident,)).fetchone())

    def summary(self):
        self.source('second', 'owner')
        self.memory('summary')
        for source in ['source', 'second']:
            self.db.execute("INSERT INTO memory_summary_sources VALUES ('summary',?,'owner','hash')", (source,))

    def test_source_delete_scrubs_candidate_and_preserves_other_owner(self):
        self.db.execute("DELETE FROM messages WHERE id='source'")
        self.assertEqual(('INVALID', ''), self.state('candidate'))
        self.assertEqual('PENDING', self.state('foreign')[0])

    def test_each_source_edit_invalidates_pending_and_running_tasks(self):
        for expression in ["content='changed'", "status='FAILED'", 'isFromUser=0', 'localOnly=1']:
            with self.subTest(expression=expression):
                self.db.execute('SAVEPOINT check_edit')
                self.candidate('task', 'source', 'owner', status='RUNNING')
                self.db.execute('UPDATE messages SET '+expression+" WHERE id='source'")
                self.assertEqual(('INVALID', ''), self.state('candidate'))
                self.assertEqual('INVALID', self.state('task')[0])
                self.db.execute('ROLLBACK TO check_edit')
                self.db.execute('RELEASE check_edit')

    def test_manual_confirmation_survives_history_exclusion(self):
        self.db.execute("UPDATE messages SET excludeFromHistory=1 WHERE id='source'")
        self.assertEqual('PENDING', self.state('candidate')[0])

    def test_automatic_task_invalidated_by_history_exclusion(self):
        self.candidate('task', 'source', 'owner', manual=0, status='RUNNING')
        self.db.execute("UPDATE messages SET excludeFromHistory=1 WHERE id='source'")
        self.assertEqual('INVALID', self.state('task')[0])

    def test_any_summary_source_change_tombstones_all_derived_content(self):
        self.summary()
        self.db.execute("UPDATE messages SET content='changed' WHERE id='second'")
        row=self.db.execute("SELECT deleted,content,cloudAllowed FROM memories WHERE id='summary'").fetchone()
        self.assertEqual((1, '', 0), tuple(row))
        self.assertEqual(0, self.db.execute('SELECT count(*) FROM memory_summary_sources').fetchone()[0])

    def test_memory_deletion_invalidates_linked_candidates_and_tasks(self):
        self.memory('saved')
        self.db.execute("UPDATE memories SET sourceMessageId='source' WHERE id='saved'")
        self.candidate('task', 'source', 'owner', status='RUNNING')
        self.db.execute("UPDATE memories SET deleted=1,content='' WHERE id='saved'")
        self.assertEqual('INVALID', self.state('candidate')[0])
        self.assertEqual('INVALID', self.state('task')[0])
        self.assertEqual('PENDING', self.state('foreign')[0])

    def test_unrelated_memory_deletion_preserves_independent_explicit_work(self):
        self.memory('unrelated')
        self.candidate('task', 'source', 'owner', manual=1, status='RUNNING')
        self.db.execute("UPDATE memories SET deleted=1,content='' WHERE id='unrelated'")
        self.assertEqual('PENDING',self.state('candidate')[0])
        self.assertEqual('RUNNING',self.state('task')[0])
        self.db.execute("UPDATE messages SET excludeFromHistory=1 WHERE id='source'")
        self.assertEqual('RUNNING',self.state('task')[0])

    def test_ignore_invalidates_existing_source_tasks_before_their_reopen(self):
        self.candidate('task', 'source', 'owner', status='RUNNING')
        self.db.execute(self.q['finish'],dict(owner='owner',id='candidate',status='IGNORED',memoryId=None))
        self.db.execute(self.q['invalidateTasksForSource'],dict(owner='owner',sourceId='source'))
        self.assertEqual('INVALID',self.state('task')[0])
        self.assertEqual(0,self.db.execute(self.q['finishTask'],dict(owner='owner',id='task',status='DONE')).rowcount)
        self.assertEqual(('IGNORED',''),self.state('candidate'))

    def test_ignored_candidate_cannot_be_inserted_again_automatically(self):
        self.assertEqual(1,self.db.execute(self.q['finish'],dict(owner='owner',id='candidate',status='IGNORED',memoryId=None)).rowcount)
        self.db.execute("INSERT OR IGNORE INTO memory_candidates SELECT * FROM memory_candidates WHERE id='candidate'")
        self.assertEqual(('IGNORED', ''),self.state('candidate'))

    def test_owner_scoped_confirmation_is_compare_and_set(self):
        args=dict(owner='foreign', id='candidate',status='ACCEPTED',memoryId='memory')
        self.assertEqual(0,self.db.execute(self.q['finish'],args).rowcount)
        args['owner']='owner'
        self.assertEqual(1,self.db.execute(self.q['finish'],args).rowcount)
        self.assertEqual(0,self.db.execute(self.q['finish'],args).rowcount)

    def test_confirmation_transaction_rolls_back_claim_if_memory_save_fails(self):
        try:
            with self.db:
                self.db.execute(self.q['finish'],dict(owner='owner',id='candidate',status='ACCEPTED',memoryId='memory'))
                self.db.execute("INSERT INTO memories(id) VALUES ('duplicate'),('duplicate')")
        except sqlite3.IntegrityError:
            pass
        self.assertEqual('PENDING',self.state('candidate')[0])

    def test_summary_eligibility_excludes_assistant_private_history_and_failed_rows(self):
        self.db.execute("UPDATE messages SET excludeFromHistory=1 WHERE id='source'")
        self.source('assistant','owner')
        self.db.execute("UPDATE messages SET isFromUser=0 WHERE id='assistant'")
        self.assertEqual([],list(self.db.execute(self.q['summaryMessages'],dict(owner='owner',persona='persona'))))

    def test_running_task_recovery_preserves_only_this_process_active_tasks(self):
        self.candidate('abandoned', 'source', 'owner', status='RUNNING')
        self.candidate('active', 'source', 'owner', status='RUNNING')
        # Expand the Room list bind using the same fixed list used in this fixture.
        sql=self.q['recoverTasks'].replace(':activeIds', ':active')
        self.db.execute(sql, dict(owner='owner',active='active'))
        self.assertEqual('FAILED',self.state('abandoned')[0])
        self.assertEqual('RUNNING',self.state('active')[0])

    def test_explicit_reopen_is_owner_scoped(self):
        self.db.execute(self.q['finish'],dict(owner='owner',id='candidate',status='IGNORED',memoryId=None))
        self.assertEqual(0,self.db.execute(self.q['reopen'],dict(owner='foreign',id='candidate',content='changed')).rowcount)
        self.assertEqual(1,self.db.execute(self.q['reopen'],dict(owner='owner',id='candidate',content='confirmed')).rowcount)
        self.assertEqual(('PENDING','confirmed'),self.state('candidate'))

    def test_source_edit_then_restore_does_not_restore_a_task(self):
        self.candidate('task', 'source', 'owner', status='RUNNING')
        self.db.execute("UPDATE messages SET content='changed' WHERE id='source'")
        self.db.execute("UPDATE messages SET content='我喜欢简洁回答' WHERE id='source'")
        self.assertEqual('INVALID',self.state('task')[0])
        self.assertEqual(0,self.db.execute(self.q['finishTask'],dict(owner='owner',id='task',status='DONE')).rowcount)

    def test_foreign_owner_cannot_rebind_confirmation(self):
        self.db.execute(self.q['finish'],dict(owner='owner',id='candidate',status='ACCEPTED',memoryId='memory'))
        self.db.execute(self.q['bindAcceptedMemory'],dict(owner='foreign',id='candidate',memoryId='wrong'))
        self.assertEqual('memory',self.db.execute("SELECT memoryId FROM memory_candidates WHERE id='candidate'").fetchone()[0])


if __name__ == '__main__':
    unittest.main()
