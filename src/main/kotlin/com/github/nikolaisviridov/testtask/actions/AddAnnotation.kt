package com.github.nikolaisviridov.testtask.actions

import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.template.TemplateBuilderFactory
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil
import com.jetbrains.python.codeInsight.intentions.*
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil.AnnotationInfo
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil.Pep484IncompatibleTypeException
import com.jetbrains.python.documentation.PythonDocumentationProvider
import com.jetbrains.python.documentation.doctest.PyDocstringFile
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyAugAssignmentStatementNavigator
import com.jetbrains.python.psi.impl.PyBuiltinCache
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.resolve.RatedResolveResult
import com.jetbrains.python.psi.types.PyClassTypeImpl
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.TypeEvalContext
import one.util.streamex.StreamEx
import java.util.*
import java.util.function.Predicate

class AddAnnotation : PyBaseIntentionAction() {

    override fun getText(): String = "TestTask Add annotation"

    override fun getFamilyName(): String = "Test Task FamilyName"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is PyFile || file is PyDocstringFile || editor == null) {
            return false
        }

        val resolved = findSuitableTargetsUnderCaret(project, editor, file)

        return resolved.isNotEmpty()
    }

    private fun traverseTree(element: PsiElement, result: MutableList<PsiElement> = arrayListOf()): List<PsiElement> {
        if (element is PyTargetExpression || element is PyReferenceExpression) {
            result.add(element)
        }
        for (child in element.children) {
            traverseTree(child, result)
        }
        return result
    }

    private fun findSuitableTargetsUnderCaret(project: Project, editor: Editor, file: PsiFile): List<PyTargetExpression> {
        val index = ProjectFileIndex.getInstance(project)
        val typeEvalContext = TypeEvalContext.codeAnalysis(project, file)

        // TODO filter out targets defined in stubs
        return traverseTree(file)
                .filterIsInstance<PyTargetExpression>()
                .filter { !index.isInLibraryClasses(it.containingFile.virtualFile) }
                .filter { canBeAnnotated(it) }
                .filter { !isAnnotated(it, typeEvalContext) }
    }

    private fun resolveReferenceAugAssignmentsAware(element: PyReferenceOwner,
                                                    resolveContext: PyResolveContext): StreamEx<PsiElement> {
        return StreamEx.of(PyUtil.multiResolveTopPriority(element, resolveContext))
                .filter { resolved: PsiElement -> resolved is PyTargetExpression || resolved !== element }
                .flatMap { resolved: PsiElement -> expandResolveAugAssignments(resolved, resolveContext) }
                .distinct()
    }

    private fun expandResolveAugAssignments(element: PsiElement, context: PyResolveContext): StreamEx<PsiElement> {
        return if (element is PyReferenceExpression && PyAugAssignmentStatementNavigator.getStatementByTarget(element) != null) {
            StreamEx.of(resolveReferenceAugAssignmentsAware(element as PyReferenceOwner, context))
        } else StreamEx.of(element)
    }

    private fun canBeAnnotated(target: PyTargetExpression): Boolean {
        val directParent = target.parent
        return if (directParent is PyImportElement ||
                directParent is PyComprehensionForComponent ||
                directParent is PyGlobalStatement ||
                directParent is PyNonlocalStatement) {
            false
        } else PsiTreeUtil.getParentOfType(target, PyWithItem::class.java, PyAssignmentStatement::class.java, PyForPart::class.java) != null
    }

    // TODO unify this logic with PyTypingTypeProvider somehow
    private fun isAnnotated(target: PyTargetExpression, context: TypeEvalContext): Boolean {
        val scopeOwner = ScopeUtil.getScopeOwner(target)
        val name = target.name
        if (scopeOwner == null || name == null) {
            return false
        }
        if (!target.isQualified) {
            if (hasInlineAnnotation(target)) {
                return true
            }

            var candidates: StreamEx<PyTargetExpression>? = null
            if (context.maySwitchToAST(target)) {
                val scope = ControlFlowCache.getScope(scopeOwner)
                candidates = StreamEx.of(scope.getNamedElements(name, false)).select(PyTargetExpression::class.java)
            } else if (scopeOwner is PyFile) {
                candidates = StreamEx.of(scopeOwner.topLevelAttributes).filter { t: PyTargetExpression -> name == t.name }
            } else if (scopeOwner is PyClass) {
                candidates = StreamEx.of(scopeOwner.classAttributes).filter { t: PyTargetExpression -> name == t.name }
            }

            if (candidates != null) {
                return candidates.anyMatch(Predicate { obj: PyTargetExpression -> hasInlineAnnotation(obj) })
            }
        } else if (isInstanceAttribute(target, context)) {
            // Set isDefinition=true to start searching right from the class level.
            val classLevelDefinitions = findClassLevelDefinitions(target, context)
            return ContainerUtil.exists(classLevelDefinitions, Condition { obj: PyTargetExpression -> hasInlineAnnotation(obj) })
        }
        return false
    }

    private fun findClassLevelDefinitions(target: PyTargetExpression, context: TypeEvalContext): List<PyTargetExpression> {
        assert(target.containingClass != null)
        assert(target.name != null)
        val classType = PyClassTypeImpl(target.containingClass!!, true)
        val resolveContext = PyResolveContext.defaultContext().withTypeEvalContext(context)
        val classAttrs = classType.resolveMember(target.name!!, target, AccessDirection.READ, resolveContext, true)
                ?: return emptyList()
        return StreamEx.of(classAttrs)
                .map { obj: RatedResolveResult -> obj.element }
                .select(PyTargetExpression::class.java)
                .filter { x: PyTargetExpression? -> ScopeUtil.getScopeOwner(x) is PyClass }
                .toList()
    }

    private fun isInstanceAttribute(target: PyTargetExpression, context: TypeEvalContext): Boolean {
        val scopeOwner = ScopeUtil.getScopeOwner(target)
        return if (target.isQualified && target.containingClass != null && scopeOwner is PyFunction) {
            if (context.maySwitchToAST(target)) {
                val resolveContext = PyResolveContext.defaultContext().withTypeEvalContext(context)
                StreamEx.of(PyUtil.multiResolveTopPriority(target.qualifier!!, resolveContext))
                        .select(PyParameter::class.java)
                        .filter { obj: PyParameter -> obj.isSelf }
                        .anyMatch { p: PyParameter? -> PsiTreeUtil.getParentOfType(p, PyFunction::class.java) === scopeOwner }
            } else {
                PyUtil.isInstanceAttribute(target)
            }
        } else false
    }

    private fun hasInlineAnnotation(target: PyTargetExpression): Boolean {
        return target.annotationValue != null || target.typeCommentAnnotation != null
    }

    override fun doInvoke(project: Project, editor: Editor?, file: PsiFile?) {

        if (editor == null || file == null) return

        val targets = findSuitableTargetsUnderCaret(project, editor, file)

        for (annotationTarget in targets) {
            try {
                if (preferSyntacticAnnotation(annotationTarget)) {
                    // for python 3
                    insertVariableAnnotation(annotationTarget)
                } else {
                    // for python 2
                    insertVariableTypeComment(annotationTarget)
                }
            } catch (e: Pep484IncompatibleTypeException) {
//            PythonUiService.getInstance().showErrorHint(editor, e.message)
            }
        }

    }

    private fun preferSyntacticAnnotation(annotationTarget: PyTargetExpression): Boolean {
        return LanguageLevel.forElement(annotationTarget).isAtLeast(LanguageLevel.PYTHON36)
    }

    private fun insertVariableAnnotation(target: PyTargetExpression) {
        val context = TypeEvalContext.userInitiated(target.project, target.containingFile)
        val inferredType = getInferredTypeOrObject(target, context)
        PyTypeHintGenerationUtil.checkPep484Compatibility(inferredType, context)
        val annotationText = PythonDocumentationProvider.getTypeHint(inferredType, context)
//        val info = AnnotationInfo(annotationText, inferredType)
        val info = AnnotationInfo("Any", inferredType)
        if (isInstanceAttribute(target, context)) {
            val classLevelAttrs = findClassLevelDefinitions(target, context)
            if (classLevelAttrs.isEmpty()) {
                PyTypeHintGenerationUtil.insertStandaloneAttributeAnnotation(target, context, info, false)
            } else {
                PyTypeHintGenerationUtil.insertVariableAnnotation(classLevelAttrs[0], context, info, false)
            }
        } else {
            PyTypeHintGenerationUtil.insertVariableAnnotation(target, context, info, false)
        }
    }

    private fun insertVariableTypeComment(target: PyTargetExpression) {
        val context = TypeEvalContext.userInitiated(target.project, target.containingFile)
        val info = generateNestedTypeHint(target, context)
        if (isInstanceAttribute(target, context)) {
            val classLevelAttrs = findClassLevelDefinitions(target, context)
            if (classLevelAttrs.isEmpty()) {
                PyTypeHintGenerationUtil.insertStandaloneAttributeTypeComment(target, context, info, false)
            } else {
                // Use existing class level definition (say, assignment of the default value) for annotation
                PyTypeHintGenerationUtil.insertVariableTypeComment(classLevelAttrs[0], context, info, false)
            }
        } else {
            PyTypeHintGenerationUtil.insertVariableTypeComment(target, context, info, false)
        }
    }

    private fun generateNestedTypeHint(target: PyTargetExpression, context: TypeEvalContext): AnnotationInfo {
        val validTargetParent = PsiTreeUtil.getParentOfType(target, PyForPart::class.java, PyWithItem::class.java, PyAssignmentStatement::class.java)!!
        val topmostTarget = PsiTreeUtil.findPrevParent(validTargetParent, target)
        val builder = StringBuilder()
        val types: MutableList<PyType?> = ArrayList()
        val typeRanges = ArrayList<TextRange>()
        generateNestedTypeHint(topmostTarget, context, builder, types, typeRanges)
        return AnnotationInfo(builder.toString(), types, typeRanges)
    }

    private fun generateNestedTypeHint(target: PsiElement,
                                       context: TypeEvalContext,
                                       builder: StringBuilder,
                                       types: MutableList<PyType?>,
                                       typeRanges: MutableList<TextRange>) {
        if (target is PyParenthesizedExpression) {
            val contained = target.containedExpression
            contained?.let { generateNestedTypeHint(it, context, builder, types, typeRanges) }
        } else if (target is PyTupleExpression) {
            builder.append("(")
            val elements = target.elements
            for (i in elements.indices) {
                if (i > 0) {
                    builder.append(", ")
                }
                generateNestedTypeHint(elements[i], context, builder, types, typeRanges)
            }
            builder.append(")")
        } else if (target is PyTypedElement) {
            val singleTargetType = getInferredTypeOrObject(target, context)
            PyTypeHintGenerationUtil.checkPep484Compatibility(singleTargetType, context)
            val singleTargetAnnotation = PythonDocumentationProvider.getTypeHint(singleTargetType, context)
            types.add(singleTargetType)
            typeRanges.add(TextRange.from(builder.length, singleTargetAnnotation.length))
            builder.append(singleTargetAnnotation)
        }
    }

    private fun getInferredTypeOrObject(target: PyTypedElement, context: TypeEvalContext): PyType? {
        val inferred = context.getType(target)
        return inferred ?: PyBuiltinCache.getInstance(target).objectType
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

//    private fun findSuitableFunction(editor: Editor, file: PsiFile): PyFunction? {
//        return TypeIntention.findOnlySuitableFunction(editor, file) { input: PyFunction? -> true }
//    }
//
//    fun annotateTypes(editor: Editor?, function: PyFunction) {
//        if (!FileModificationService.getInstance().preparePsiElementForWrite(function)) return
//        WriteAction.run<RuntimeException> {
//            if (PyAnnotateTypesIntention.isPy3k(function.containingFile)) {
//                PyAnnotateTypesIntention.generatePy3kTypeAnnotations(function.project, editor, function)
//            } else {
//                PyAnnotateTypesIntention.generateTypeCommentAnnotations(function.project, function)
//            }
//        }
//    }
//
//    private fun isPy3k(file: PsiFile): Boolean {
//        return !LanguageLevel.forElement(file).isPython2
//    }
//
//    private fun generatePy3kTypeAnnotations(project: Project, editor: Editor, function: PyFunction) {
//        val builder = TemplateBuilderFactory.getInstance().createTemplateBuilder(function)
//        val returnType = SpecifyTypeInPy3AnnotationsIntention.annotateReturnType(project, function, false)
//        if (returnType != null) {
//            builder.replaceElement(returnType, returnType.text)
//        }
//        val params = function.parameterList.parameters
//        for (i in params.indices.reversed()) {
//            if (params[i] is PyNamedParameter && !params[i].isSelf) {
//                params[i] = SpecifyTypeInPy3AnnotationsIntention.annotateParameter(project, editor, (params[i] as PyNamedParameter), false)
//            }
//        }
//        for (i in params.indices.reversed()) {
//            if (params[i] is PyNamedParameter) {
//                if (!params[i].isSelf) {
//                    params[i] = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(params[i])
//                    val annotation = (params[i] as PyNamedParameter).annotation
//                    if (annotation != null) {
//                        val annotationValue = annotation.value
//                        if (annotationValue != null) {
//                            builder.replaceElement(annotationValue, annotationValue.text)
//                        }
//                    }
//                }
//            }
//        }
//        PyAnnotateTypesIntention.startTemplate(project, function, builder)
//    }


}
