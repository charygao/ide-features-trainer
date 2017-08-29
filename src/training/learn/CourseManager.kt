package training.learn

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import training.actions.OpenLessonAction
import training.lang.LangManager
import training.lang.LangSupport
import training.learn.exceptons.InvalidSdkException
import training.learn.exceptons.NoJavaModuleException
import training.learn.exceptons.NoSdkException
import training.learn.exceptons.OldJdkException
import training.learn.lesson.Lesson
import training.util.GenModuleXml
import training.util.GenModuleXml.MODULE_MODULES_PATH
import java.util.*

class CourseManager internal constructor() {

  var learnProject: Project? = null
  var learnProjectPath: String? = null
  var mapModuleVirtualFile = HashMap<Module, VirtualFile>()
  val modules: MutableList<Module> = ArrayList<Module>()
  val currentProject: Project?
    get() {
      val lastFocusedFrame = IdeFocusManager.getGlobalInstance().lastFocusedFrame ?: return null
      return lastFocusedFrame.project
    }


  init {
    initModules()
  }

  private fun initModules() {
    val modulesRoot = Module.getRootFromPath(GenModuleXml.MODULE_ALLMODULE_FILENAME)
    for (element in modulesRoot.children) {
      if (element.name == GenModuleXml.MODULE_TYPE_ATTR) {
        val moduleFilename = element.getAttribute(GenModuleXml.MODULE_NAME_ATTR).value
        val module = Module.initModule(moduleFilename, MODULE_MODULES_PATH) ?: throw Exception("Unable to init module (is null) from file: $moduleFilename")
        modules.add(module)
      }
    }
  }

  fun getModuleById(id: String): Module? {
    val modules = modules
    if (modules.isEmpty()) return null
    return modules.firstOrNull { it.id?.toUpperCase() == id.toUpperCase() }
  }

  fun registerVirtualFile(module: Module, virtualFile: VirtualFile) {
    mapModuleVirtualFile.put(module, virtualFile)
  }

  fun isVirtualFileRegistered(virtualFile: VirtualFile): Boolean {
    return mapModuleVirtualFile.containsValue(virtualFile)
  }

  fun unregisterVirtualFile(virtualFile: VirtualFile) {
    if (!mapModuleVirtualFile.containsValue(virtualFile)) return
    for (module in mapModuleVirtualFile.keys) {
      if (mapModuleVirtualFile[module] == virtualFile) {
        mapModuleVirtualFile.remove(module)
        return
      }
    }
  }

  fun unregisterModule(module: Module) {
    mapModuleVirtualFile.remove(module)
  }


  @Synchronized fun openLesson(project: Project, lesson: Lesson?) {
    val action = ActionManager.getInstance().getAction("learn.open.lesson")
    val focusOwner = IdeFocusManager.getInstance(project).focusOwner
    val parent = DataManager.getInstance().getDataContext(focusOwner)
    val context = SimpleDataContext.getSimpleContext(OpenLessonAction.LESSON_DATA_KEY.name, lesson, parent)
    val event = AnActionEvent.createFromAnAction(action, null, "", context)
    ActionUtil.performActionDumbAware(action, event)

  }

  /**
   * checking environment to start learning plugin. Checking SDK.

   * @param project where lesson should be started
   * *
   * @throws OldJdkException     - if project JDK version is not enough for this module
   * *
   * @throws InvalidSdkException - if project SDK is not suitable for module
   */
  @Throws(OldJdkException::class, InvalidSdkException::class, NoSdkException::class, NoJavaModuleException::class)
  fun checkEnvironment(project: Project) {
    val sdk = ProjectRootManager.getInstance(project).projectSdk
    if (LangManager.getInstance().getLangSupport().needToCheckSDK()) {
      val sdkType = sdk?.sdkType ?: throw NoSdkException()
      LangManager.getInstance().getLangSupport().checkSdkCompatibility(sdk!!, sdkType)
    }
  }

  fun findLesson(lessonName: String): Lesson? {
    return modules
        .flatMap { it.lessons }
        .firstOrNull { it.name.toUpperCase() == lessonName.toUpperCase() }
  }

  fun updateModules() {
    modules.forEach { it.update() }
  }


  fun calcNotPassedLessons(): Int {
    if (modules.isEmpty()) return 0
    return modules
        .flatMap { it.lessons }
        .count { !it.passed }
  }

  fun calcPassedLessons(): Int {
    if (modules.isEmpty()) return 0
    return modules
        .flatMap { it.lessons }
        .count { it.passed }
  }

  /**
   * @return null if lesson has no module or it is only one lesson in module
   */
  fun giveNextLesson(currentLesson: Lesson): Lesson? {
    val module = currentLesson.module ?: throw Exception("Current lesson doesn't have parent module")
    val lessons = module.lessons
    val size = lessons.size
    if (size == 1) return null
    return (0..size - 1)
        .filter { lessons[it] == currentLesson }
        .takeWhile { it + 1 < size }
        .firstOrNull()
        ?.let { lessons[it + 1] }
  }

  fun giveNextModule(currentLesson: Lesson): Module? {
    var nextModule: Module? = null
    val module = currentLesson.module
    val modules = CourseManager.instance.modules ?: return null
    val size = modules.size
    if (size == 1) return null

    for (i in 0..size - 1) {
      if (modules[i] == module) {
        if (i + 1 < size) nextModule = modules[i + 1]
        break
      }
    }
    if (nextModule == null || nextModule.lessons.isEmpty()) return null
    return nextModule
  }

  fun calcLessonsForLanguage(langSupport: LangSupport): Int {
    val inc = Ref(0)
    modules.forEach { module -> inc.set(inc.get() + module.filterLessonByLang(langSupport).size) }
    return inc.get()
  }

  companion object {
    val LEARN_PROJECT_NAME = "LearnProject"
    val NOTIFICATION_ID = "Training plugin"

    val instance: CourseManager
      get() = ServiceManager.getService(CourseManager::class.java)
  }

}
