package com.github.nikolaisviridov.testtask.services

import com.intellij.openapi.project.Project
import com.github.nikolaisviridov.testtask.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
