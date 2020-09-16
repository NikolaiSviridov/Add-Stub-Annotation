package com.github.nikolaisviridov.testtask.intentions

import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil
import com.jetbrains.python.codeInsight.intentions.PyBaseIntentionAction
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil.AnnotationInfo
import com.jetbrains.python.codeInsight.intentions.PyTypeHintGenerationUtil.Pep484IncompatibleTypeException
import com.jetbrains.python.documentation.doctest.PyDocstringFile
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyAugAssignmentStatementNavigator
import com.jetbrains.python.psi.impl.PyBuiltinCache
import com.jetbrains.python.psi.impl.PyPsiUtils
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.types.PyClassTypeImpl
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.TypeEvalContext
import one.util.streamex.StreamEx
import java.util.function.Predicate

class AddAnnotation : PyBaseIntentionAction() {

    override fun getText(): String = "TestTask Add stub-annotation"

    override fun getFamilyName(): String = "Test Task FamilyName"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is PyFile || file is PyDocstringFile || editor == null) {
            return false
        }

        val resolvedVars = findSuitableTargets(project, file)
        val resolvedFunctions = traversePsiTreeFunctions(file)

        return resolvedVars.isNotEmpty() || resolvedFunctions.isNotEmpty()
    }

    override fun doInvoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val targets = findSuitableTargets(project, file)
        for (annotationTarget in targets) {
            try {
                insertVariableAnnotation(annotationTarget)
            } catch (e: Pep484IncompatibleTypeException) {
                e.message?.let { HintManager.getInstance().showErrorHint(editor, it) }
            }
        }

        val functions = traversePsiTreeFunctions(file)
        for (function in functions) {
            annotateTypes(editor, function)
        }
    }

    override fun startInWriteAction(): Boolean = false

    private fun getParameterType(): String = "Any"

    private fun getFunctionType(): String = "Any"

    private fun getVariableType(target: PyTypedElement): PyType? =
            PyBuiltinCache.getInstance(target).getObjectType("Any")

    private fun getVariableAnnotationText(): String = "Any"

    private fun traversePsiTreeVariables(element: PsiElement, result: MutableList<PyTargetExpression> = arrayListOf())
            : List<PyTargetExpression> {
        if (element is PyTargetExpression) {
            result.add(element)
        }
        for (child in element.children) {
            traversePsiTreeVariables(child, result)
        }
        return result
    }

    private fun traversePsiTreeFunctions(element: PsiElement, result: MutableList<PyFunction> = arrayListOf())
            : List<PyFunction> {
        val index = ProjectFileIndex.getInstance(element.project)
        if (element is PyFunction) {
            if (!index.isInLibraryClasses(element.containingFile.virtualFile)) {
                result.add(element)
            }
        }
        for (child in element.children) {
            traversePsiTreeFunctions(child, result)
        }
        return result
    }

    private fun findSuitableTargets(project: Project, file: PsiFile): List<PyTargetExpression> {
        val index = ProjectFileIndex.getInstance(project)
        val typeEvalContext = TypeEvalContext.codeAnalysis(project, file)

        return traversePsiTreeVariables(file)
                .filter { !index.isInLibraryClasses(it.containingFile.virtualFile) }
                .filter { canBeAnnotated(it) }
                .filter { !isAnnotated(it, typeEvalContext) }
    }

    private fun resolveReferenceAugAssignmentsAware(element: PyReferenceOwner,
                                                    resolveContext: PyResolveContext): StreamEx<PsiElement> {
        return StreamEx.of(PyUtil.multiResolveTopPriority(element, resolveContext))
                .filter { it is PyTargetExpression || it !== element }
                .flatMap { expandResolveAugAssignments(it, resolveContext) }
                .distinct()
    }

    private fun expandResolveAugAssignments(element: PsiElement, context: PyResolveContext): StreamEx<PsiElement> {
        return if (element is PyReferenceExpression
                && PyAugAssignmentStatementNavigator.getStatementByTarget(element) != null) {
            StreamEx.of(resolveReferenceAugAssignmentsAware(element as PyReferenceOwner, context))
        } else {
            StreamEx.of(element)
        }
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

    private fun isAnnotated(target: PyTargetExpression, context: TypeEvalContext): Boolean {
        val scopeOwner = ScopeUtil.getScopeOwner(target) ?: return false
        val name = target.name ?: return false

        if (!target.isQualified) {
            if (hasInlineAnnotation(target)) return true

            var candidates: StreamEx<PyTargetExpression>? = null
            if (context.maySwitchToAST(target)) {
                val scope = ControlFlowCache.getScope(scopeOwner)
                candidates = StreamEx.of(scope.getNamedElements(name, false)).select(PyTargetExpression::class.java)
            } else if (scopeOwner is PyFile) {
                candidates = StreamEx.of(scopeOwner.topLevelAttributes).filter { name == it.name }
            } else if (scopeOwner is PyClass) {
                candidates = StreamEx.of(scopeOwner.classAttributes).filter { name == it.name }
            }

            if (candidates != null) {
                return candidates.anyMatch(Predicate { hasInlineAnnotation(it) })
            }
        } else if (isInstanceAttribute(target, context)) {
            val classLevelDefinitions = findClassLevelDefinitions(target, context)
            return ContainerUtil.exists(classLevelDefinitions, { hasInlineAnnotation(it) })
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
                .map { it.element }
                .select(PyTargetExpression::class.java)
                .filter { ScopeUtil.getScopeOwner(it) is PyClass }
                .toList()
    }

    private fun isInstanceAttribute(target: PyTargetExpression, context: TypeEvalContext): Boolean {
        val scopeOwner = ScopeUtil.getScopeOwner(target)
        return if (target.isQualified && target.containingClass != null && scopeOwner is PyFunction) {
            if (context.maySwitchToAST(target)) {
                val resolveContext = PyResolveContext.defaultContext().withTypeEvalContext(context)
                StreamEx.of(PyUtil.multiResolveTopPriority(target.qualifier!!, resolveContext))
                        .select(PyParameter::class.java)
                        .filter { it.isSelf }
                        .anyMatch { PsiTreeUtil.getParentOfType(it, PyFunction::class.java) === scopeOwner }
            } else {
                PyUtil.isInstanceAttribute(target)
            }
        } else false
    }

    private fun hasInlineAnnotation(target: PyTargetExpression): Boolean {
        return target.annotationValue != null || target.typeCommentAnnotation != null
    }

    private fun insertVariableAnnotation(target: PyTargetExpression) {
        val context = TypeEvalContext.userInitiated(target.project, target.containingFile)
        val inferredType = getVariableType(target)
        PyTypeHintGenerationUtil.checkPep484Compatibility(inferredType, context)
        val info = AnnotationInfo(getVariableAnnotationText(), inferredType)
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

    private fun annotateTypes(editor: Editor?, function: PyFunction) {
        if (editor == null) return
        if (!FileModificationService.getInstance().preparePsiElementForWrite(function)) return

        WriteAction.run<RuntimeException> { generatePy3kTypeAnnotations(function.project, editor, function) }
    }


    private fun annotateParameter(project: Project?,
                                  editor: Editor,
                                  parameter: PyNamedParameter): PyNamedParameter? {
        @Suppress("NAME_SHADOWING")
        var parameter = parameter
        val defaultParamValue = parameter.defaultValue
        val paramName = StringUtil.notNullize(parameter.name)
        val elementGenerator = PyElementGenerator.getInstance(project)
        val defaultParamText = defaultParamValue?.text
        val paramType = getParameterType()
        val namedParameter = elementGenerator.createParameter(paramName, defaultParamText, paramType,
                LanguageLevel.forElement(parameter))!!
        parameter = parameter.replace(namedParameter) as PyNamedParameter
        parameter = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(parameter)
        editor.caretModel.moveToOffset(parameter.textOffset)
        return parameter
    }

    private fun annotateReturnType(function: PyFunction): PyExpression? {
        val returnType = getFunctionType()
        val annotationText = "-> $returnType"
        val annotatedFunction = PyUtil.updateDocumentUnblockedAndCommitted<PyFunction>(function) { document: Document ->
            val oldAnnotation = function.annotation
            if (oldAnnotation != null) {
                val oldRange = oldAnnotation.textRange
                document.replaceString(oldRange.startOffset, oldRange.endOffset, annotationText)
            } else {
                val prevElem = PyPsiUtils.getPrevNonCommentSibling(function.statementList, true)!!
                val range = prevElem.textRange
                if (prevElem.node.elementType === PyTokenTypes.COLON) {
                    document.insertString(range.startOffset, " $annotationText")
                } else {
                    document.insertString(range.endOffset, " $annotationText:")
                }
            }
            CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(function)
        } ?: return null

        val annotation = annotatedFunction.annotation!!
        return annotation.value ?: error("Generated function must have annotation")
    }

    private fun generatePy3kTypeAnnotations(project: Project, editor: Editor, function: PyFunction) {
        annotateReturnType(function)
        val params = function.parameterList.parameters
        for (i in params.indices.reversed()) {
            if (params[i] is PyNamedParameter && !params[i].isSelf) {
                annotateParameter(project, editor, (params[i] as PyNamedParameter))
            }
        }
    }

}
