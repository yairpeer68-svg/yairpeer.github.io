package com.sherlock.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sherlock.app.data.model.Priority
import com.sherlock.app.data.model.Project
import com.sherlock.app.data.model.ProjectStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProjectDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.projectDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetById_returnsTheSameProject() = runBlocking {
        val id = dao.insert(Project(name = "תיק חקירה", priority = Priority.HIGH))

        val loaded = dao.getById(id)

        assertEquals("תיק חקירה", loaded?.name)
        assertEquals(Priority.HIGH, loaded?.priority)
    }

    @Test
    fun getAll_excludesDeletedProjects() = runBlocking {
        dao.insert(Project(name = "פעיל"))
        val deletedId = dao.insert(Project(name = "נמחק", isDeleted = true, deletedAt = System.currentTimeMillis()))

        val active = dao.getAll().first()

        assertEquals(1, active.size)
        assertEquals("פעיל", active[0].name)
        assertTrue(active.none { it.id == deletedId })
    }

    @Test
    fun getDeleted_returnsOnlyDeletedProjects() = runBlocking {
        dao.insert(Project(name = "פעיל"))
        dao.insert(Project(name = "נמחק", isDeleted = true, deletedAt = System.currentTimeMillis()))

        val deleted = dao.getDeleted().first()

        assertEquals(1, deleted.size)
        assertEquals("נמחק", deleted[0].name)
    }

    @Test
    fun update_changesPersistedFields() = runBlocking {
        val id = dao.insert(Project(name = "ישן", status = ProjectStatus.ACTIVE))
        val original = dao.getById(id)!!

        dao.update(original.copy(name = "חדש", status = ProjectStatus.CLOSED, isPinned = true))
        val updated = dao.getById(id)

        assertEquals("חדש", updated?.name)
        assertEquals(ProjectStatus.CLOSED, updated?.status)
        assertEquals(true, updated?.isPinned)
    }

    @Test
    fun delete_removesProjectPermanently() = runBlocking {
        val id = dao.insert(Project(name = "למחיקה"))
        val project = dao.getById(id)!!

        dao.delete(project)

        assertNull(dao.getById(id))
    }

    @Test
    fun getCount_reflectsNumberOfInsertedProjects() = runBlocking {
        assertEquals(0, dao.getCount())

        dao.insert(Project(name = "אחד"))
        dao.insert(Project(name = "שניים"))

        assertEquals(2, dao.getCount())
    }
}
