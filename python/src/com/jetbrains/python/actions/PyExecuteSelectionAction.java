// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.actions;

import com.google.common.collect.Lists;
import com.intellij.execution.ExecutionHelper;
import com.intellij.execution.console.LanguageConsoleView;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.Consumer;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.jetbrains.python.console.*;
import com.jetbrains.python.psi.PyFile;
import com.jetbrains.python.run.PythonRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class PyExecuteSelectionAction extends AnAction {

  public static final String EXECUTE_SELECTION_IN_CONSOLE = "Execute Selection in Console";

  public PyExecuteSelectionAction() {
    super(EXECUTE_SELECTION_IN_CONSOLE);
  }

  private static final String PYCHARM_REPL_SRC = "# -*- coding: utf-8 -*-\n" +
                                                 "\n" +
                                                 "import ast\n" +
                                                 "import code\n" +
                                                 "import sys\n" +
                                                 "import textwrap\n" +
                                                 "\n" +
                                                 "ERROR_STRING = \"syntax_error_in_python_source_code\"\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "def list_rindex(l, e):\n" +
                                                 "\t\"list.rindex\"\n" +
                                                 "\tfor i, ele in enumerate(reversed(l)):\n" +
                                                 "\t\tif ele == e:\n" +
                                                 "\t\t\treturn len(l) - i - 1\n" +
                                                 "\traise ValueError(repr(e) + \" is not in list\")\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "def dedent_code(src):\n" +
                                                 "\treturn textwrap.dedent(src.rstrip()).rstrip()\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "try:\n" +
                                                 "\t# if IPython is not installed assume all input is standard python\n" +
                                                 "\timport IPython.core.inputsplitter\n" +
                                                 "except ImportError as e:\n" +
                                                 "\tto_std_python = dedent_code\n" +
                                                 "else:\n" +
                                                 "\tdef to_std_python(src):\n" +
                                                 "\t\t\"\"\"convert ipython code to standard python code\n" +
                                                 "\t\t1. can dedent code\n" +
                                                 "\t\t2. convert magic commands to standard python code\n" +
                                                 "\t\t3. line continuation character is taken away\n" +
                                                 "\t\t\tto_std_python('a\\\\')=='a'\n" +
                                                 "\t\t\"\"\"\n" +
                                                 "\t\t# https://ipython.readthedocs.org/en/latest/api/generated/IPython.core.inputsplitter.html\n" +
                                                 "\t\tisp = IPython.core.inputsplitter.IPythonInputSplitter()\n" +
                                                 "\t\tisp.push(src)\n" +
                                                 "\t\treturn isp.source_reset().rstrip()\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "def repl_input_on_error(transformed_srcs):\n" +
                                                 "\timport traceback\n" +
                                                 "\ttry:\n" +
                                                 "\t\tfor i, e in enumerate(transformed_srcs):\n" +
                                                 "\t\t\ttry:\n" +
                                                 "\t\t\t\tcompile(e, \"\", \"exec\")\n" +
                                                 "\t\t\texcept SyntaxError as e:\n" +
                                                 "\t\t\t\tif \"EOF\" not in str(e) or i + 1 == len(transformed_srcs):\n" +
                                                 "\t\t\t\t\traise\n" +
                                                 "\texcept (SyntaxError, ValueError) as e:\n" +
                                                 "\t\treturn \"\".join(traceback.format_exception_only(*sys.exc_info()[:2]))\n" +
                                                 "\traise RuntimeError(\"unreachable code\")\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "def smallest_code_block(src):\n" +
                                                 "\t\"\"\"if input is some valid code, return a pair containing number of lines, and code for REPL\n" +
                                                 "\telse return a pair of (None,error message)\n" +
                                                 "\t\"\"\"\n" +
                                                 "\t##### don't print anything in this function\n" +
                                                 "\t## don't use splitlines it splits on more substrings\n" +
                                                 "\t# https://docs.python.org/dev/library/stdtypes.html#str.splitlines\n" +
                                                 "\tsrc_lines = src.split(\"\\n\")\n" +
                                                 "\tsrc_lines_cumulative = ['\\n'.join(src_lines[:i + 1]) for i in range(len(src_lines))]\n" +
                                                 "\tbodylens = [None] * len(src_lines)\n" +
                                                 "\tlen_astdump = [None] * len(src_lines)\n" +
                                                 "\ttransformed_srcs = [None] * len(src_lines)\n" +
                                                 "\tif len(src_lines) > 0:\n" +
                                                 "\t\ttt = to_std_python(src_lines_cumulative[0])\n" +
                                                 "\t\tis_ipython_code = tt.startswith(\"get_ipython().\") and not src_lines_cumulative[0].lstrip().startswith(\"get_ipython().\")\n" +
                                                 "\t\tto_std_python2 = to_std_python if is_ipython_code else dedent_code\n" +
                                                 "\tfor i in range(len(src_lines)):\n" +
                                                 "\t\ttransformed_srcs[i] = to_std_python2(src_lines_cumulative[i])\n" +
                                                 "\t\ttry:\n" +
                                                 "\t\t\t## compile() catches\n" +
                                                 "\t\t\t## return, break, continue outside function\n" +
                                                 "\t\t\t## ast.parse('break') does not throw\n" +
                                                 "\t\t\t# incomplete_input=False\n" +
                                                 "\t\t\t## code.compile_command('with f() as a\\\\') is None\n" +
                                                 "\t\t\t# if code.compile_command(transformed_srcs[i]+\"\\n\") is None:\n" +
                                                 "\t\t\t# \tcontinue\n" +
                                                 "\t\t\tastnode = ast.parse(transformed_srcs[i])\n" +
                                                 "\t\t\tcompile(astnode, \"\", \"exec\")\n" +
                                                 "\t\t## ast.parse('1\\nif 0:')\n" +
                                                 "\t\t## IndentationError #python2\n" +
                                                 "\t\t## SyntaxError #python3, jython\n" +
                                                 "\t\texcept IndentationError as e:  # must be before SyntaxError\n" +
                                                 "\t\t\tbreak\n" +
                                                 "\t\texcept SyntaxError as e:\n" +
                                                 "\t\t\t## error messages are not portable\n" +
                                                 "\n" +
                                                 "\t\t\t# EOF_MSG=(\"EOF while scanning triple-quoted string literal\",\n" +
                                                 "\t\t\t# \t\t \"unexpected EOF while parsing\",\n" +
                                                 "\t\t\t# \t\t \"SyntaxError: mismatched input '        ' expecting EOF\")\n" +
                                                 "\t\t\tincomplete_input = type(e) is SyntaxError and \"EOF\" in str(e)\n" +
                                                 "\t\t\tif not incomplete_input:\n" +
                                                 "\t\t\t\tbreak\n" +
                                                 "\t\texcept ValueError as e:\n" +
                                                 "\t\t\tbreak\n" +
                                                 "\t\telse:\n" +
                                                 "\n" +
                                                 "\t\t\tbodylens[i] = len(astnode.body)\n" +
                                                 "\t\t\tlen_astdump[i] = len(ast.dump(astnode, False))\n" +
                                                 "\t\t\t## finding the length of ast.walk(astnode) does not work with IPython's cell magic\n" +
                                                 "\t\t\tif bodylens[i] > 1: break\n" +
                                                 "\n" +
                                                 "\tlast_eval_line = i\n" +
                                                 "\tbodylens_u = set(bodylens) - {None}\n" +
                                                 "\tif len(bodylens_u) == 0:\n" +
                                                 "\t\treturn [None, repl_input_on_error(transformed_srcs)]\n" +
                                                 "\n" +
                                                 "\t## find last line of the code to be sent\n" +
                                                 "\tmin_body_len = min(bodylens_u)\n" +
                                                 "\t## lineno is the largest line number where len(astnode.body)==min_body_len\n" +
                                                 "\tlineno = list_rindex(bodylens, min_body_len)\n" +
                                                 "\t## min_body_len == 0 means no code, eg comments\n" +
                                                 "\tif min_body_len == 0:\n" +
                                                 "\t\tlastline = lineno\n" +
                                                 "\telse:\n" +
                                                 "\t\t## find the smallest line number with same number of ast nodes as code up to line lineno\n" +
                                                 "\t\tlastline = len_astdump.index(len_astdump[lineno])\n" +
                                                 "\n" +
                                                 "\t#### if code after lastline is garbage and that code is indented, then stop.\n" +
                                                 "\tif lastline > 0 and bodylens[lastline] > 0 and \\\n" +
                                                 "\t\t\t\t\tset(bodylens[lastline + 1:last_eval_line + 1]) == {None} and \\\n" +
                                                 "\t\t\tsrc_lines[lastline + 1][:1].isspace():\n" +
                                                 "\t\treturn [None, repl_input_on_error(transformed_srcs)]\n" +
                                                 "\treturn [lastline + 1, src_lines_cumulative[lastline]]\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "def reply_as_string(input_text):\n" +
                                                 "\tsrc = input_text\n" +
                                                 "\tnlines, msg = smallest_code_block(src)\n" +
                                                 "\tif nlines is None:\n" +
                                                 "\t\treturn ERROR_STRING + \"\\n\\n\" + msg\n" +
                                                 "\treturn str(nlines) + \"\\n\\n\" + msg\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "import codecs, io\n" +
                                                 "\n" +
                                                 "utf8dec = codecs.getincrementaldecoder(\"utf-8\")\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "def as_subprocess_with_stdin():\n" +
                                                 "\t\"\"\"if invoked as subprocess with input from stdin\n" +
                                                 "\ttaking source code as stdin, if NUL characters are sent, anything after it is silently dropped\n" +
                                                 "\t\"\"\"\n" +
                                                 "\tif sys.version_info[0] == 2:\n" +
                                                 "\t\tbsrc = sys.stdin.read()\n" +
                                                 "\telse:\n" +
                                                 "\t\tbsrc = sys.stdin.buffer.read()\n" +
                                                 "\tdecoder = io.IncrementalNewlineDecoder(utf8dec(), translate=True)\n" +
                                                 "\tsrc = decoder.decode(input=bsrc, final=False)\n" +
                                                 "\treturn reply_as_string(src)\n" +
                                                 "\n" +
                                                 "\n" +
                                                 "if __name__ == '__main__':\n" +
                                                 "\tprint(as_subprocess_with_stdin())\n";

  private static void executeCodeBlock(final AnActionEvent e, final Editor editor) throws java.io.IOException {
    final ProcessBuilder pb = new ProcessBuilder("python3", "-c", PYCHARM_REPL_SRC);
    final Process proc = pb.start();
    final String lines = getLinesAfterCaret(editor);
    if (lines == null) {
      return;
    }
    try (java.io.OutputStream os = proc.getOutputStream()) {
      os.write(lines.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    final String procOut;
    try (java.io.InputStream is = proc.getInputStream()) {
      java.util.Scanner s = new java.util.Scanner(is, java.nio.charset.StandardCharsets.UTF_8.toString()).useDelimiter("\\A");
      procOut = s.hasNext() ? s.next() : "";
    }
    final String[] numLinesAndMsg = procOut.split("\n\n", 2);
    if (numLinesAndMsg[0].equals("syntax_error_in_python_source_code")) {
      showConsoleAndExecuteCode(e, java.util.Arrays.stream(numLinesAndMsg[1].split("\n")).map(line -> "# " + line)
        .collect(Collectors.joining("\n")).trim());
    }
    else {
      final int numLines = Integer.parseInt(numLinesAndMsg[0]);
      if (!numLinesAndMsg[1].trim().equals("")) {
        showConsoleAndExecuteCode(e, numLinesAndMsg[1].trim());
      }
      for (int i = 0; i < numLines; ++i) {
        moveCaretDown(editor);
      }
    }
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Editor editor = CommonDataKeys.EDITOR.getData(e.getDataContext());
    if (editor != null) {
      final String selectionText = getSelectionText(editor);
      if (selectionText != null) {
        showConsoleAndExecuteCode(e, selectionText);
      }
      else {
        try {
          executeCodeBlock(e, editor);
        }
        catch (java.io.IOException exec) {
          throw new RuntimeException(exec);
        }
      }
    }
  }

  private static void moveCaretDown(Editor editor) {
    VisualPosition pos = editor.getCaretModel().getVisualPosition();
    Pair<LogicalPosition, LogicalPosition> lines = EditorUtil.calcSurroundingRange(editor, pos, pos);
    int offset = editor.getCaretModel().getOffset();

    LogicalPosition lineStart = lines.first;
    LogicalPosition nextLineStart = lines.second;

    int start = editor.logicalPositionToOffset(lineStart);
    int end = editor.logicalPositionToOffset(nextLineStart);

    Document document = editor.getDocument();

    if (nextLineStart.line < document.getLineCount()) {

      int newOffset = end + offset - start;

      int nextLineEndOffset = document.getLineEndOffset(nextLineStart.line);
      if (newOffset >= nextLineEndOffset) {
        newOffset = nextLineEndOffset;
      }

      editor.getCaretModel().moveToOffset(newOffset);
      editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
    }
  }

  /**
   * Finds existing or creates a new console and then executes provided code there.
   *
   * @param e
   * @param selectionText null means that there is no code to execute, only open a console
   */
  public static void showConsoleAndExecuteCode(@NotNull final AnActionEvent e, @Nullable final String selectionText) {
    final Editor editor = CommonDataKeys.EDITOR.getData(e.getDataContext());
    Project project = e.getProject();
    final boolean requestFocusToConsole = selectionText == null;

    findCodeExecutor(e.getDataContext(), codeExecutor -> executeInConsole(codeExecutor, selectionText, editor), editor, project,
                     requestFocusToConsole);
  }


  /**
   * Find existing or start a new console with sdk path given and execute provided text
   * Used to run file in Python Console
   *
   * @param project current Project
   * @param selectionText text to execute
   *
   */
  public static void selectConsoleAndExecuteCode(@NotNull Project project, @Nullable final String selectionText) {
    final DataContext dataContext = DataManager.getInstance().getDataContext();
    selectConsole(dataContext, project, codeExecutor -> executeInConsole(codeExecutor, selectionText, null), null, true);
  }

  private static String getLinesAfterCaret(Editor editor) {
    VisualPosition caretPos = editor.getCaretModel().getVisualPosition();

    Pair<LogicalPosition, LogicalPosition> lines = EditorUtil.calcSurroundingRange(editor, caretPos, caretPos);

    LogicalPosition lineStart = lines.first;
    int start = editor.logicalPositionToOffset(lineStart);
    int end = editor.getDocument().getCharsSequence().length();
    if (end <= start) {
      return null;
    }
    return editor.getDocument().getCharsSequence().subSequence(start, end).toString();
  }

  private static String getLineUnderCaret(Editor editor) {
    VisualPosition caretPos = editor.getCaretModel().getVisualPosition();

    Pair<LogicalPosition, LogicalPosition> lines = EditorUtil.calcSurroundingRange(editor, caretPos, caretPos);

    LogicalPosition lineStart = lines.first;
    LogicalPosition nextLineStart = lines.second;
    int start = editor.logicalPositionToOffset(lineStart);
    int end = editor.logicalPositionToOffset(nextLineStart);
    if (end <= start) {
      return null;
    }
    return editor.getDocument().getCharsSequence().subSequence(start, end).toString();
  }

  @Nullable
  private static String getSelectionText(@NotNull Editor editor) {
    if (editor.getSelectionModel().hasSelection()) {
      SelectionModel model = editor.getSelectionModel();

      return model.getSelectedText();
    }
    else {
      return null;
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = CommonDataKeys.EDITOR.getData(e.getDataContext());
    Presentation presentation = e.getPresentation();

    boolean enabled = false;
    if (isPython(editor)) {
      String text = getSelectionText(editor);
      if (text != null) {
        presentation.setText(EXECUTE_SELECTION_IN_CONSOLE);
      }
      else {
        text = getLineUnderCaret(editor);
        if (text != null) {
          presentation.setText("Execute Line in Console");
        }
      }

      enabled = !StringUtil.isEmpty(text);
    }

    presentation.setEnabled(enabled);
    presentation.setVisible(enabled);
  }

  public static boolean isPython(Editor editor) {
    if (editor == null) {
      return false;
    }

    Project project = editor.getProject();

    if (project == null) {
      return false;
    }

    PsiFile psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    return psi instanceof PyFile;
  }

  private static void selectConsole(@NotNull DataContext dataContext, @NotNull Project project,
                                    @NotNull final Consumer<PyCodeExecutor> consumer,
                                    @Nullable Editor editor,
                                    boolean requestFocusToConsole) {
    Collection<RunContentDescriptor> consoles = getConsoles(project);

    ExecutionHelper
      .selectContentDescriptor(dataContext, project, consoles, "Select console to execute in", descriptor -> {
        if (descriptor != null && descriptor.getExecutionConsole() instanceof PyCodeExecutor) {
          ExecutionConsole console = descriptor.getExecutionConsole();
          consumer.consume((PyCodeExecutor)console);
          if (console instanceof PythonDebugLanguageConsoleView) {
            XDebugSession currentSession = XDebuggerManager.getInstance(project).getCurrentSession();
            if (currentSession != null) {
              // Select "Console" tab in case of Debug console
              ContentManager contentManager = currentSession.getUI().getContentManager();
              Content content = contentManager.findContent("Console");
              contentManager.setSelectedContent(content);
              // It's necessary to request focus again after tab selection
              if (requestFocusToConsole) {
                ((PythonDebugLanguageConsoleView)console).getPydevConsoleView().requestFocus();
              }
              else {
                if (editor != null) {
                  IdeFocusManager.findInstance().requestFocus(editor.getContentComponent(), true);
                }
              }
            }
          }
          else {
            PythonConsoleToolWindow consoleToolWindow = PythonConsoleToolWindow.getInstance(project);
            ToolWindow toolWindow = consoleToolWindow != null ? consoleToolWindow.getToolWindow() : null;
            if (toolWindow != null && !toolWindow.isVisible()) {
              toolWindow.show(null);
              ContentManager contentManager = toolWindow.getContentManager();
              Content content = contentManager.findContent(descriptor.getDisplayName());
              if (content != null) {
                contentManager.setSelectedContent(content);
              }
            }
          }
        }
      });
  }

  public static Collection<RunContentDescriptor> getConsoles(Project project) {
    PythonConsoleToolWindow toolWindow = PythonConsoleToolWindow.getInstance(project);

    if (toolWindow != null && toolWindow.getToolWindow().isVisible()) {
      RunContentDescriptor selectedContentDescriptor = toolWindow.getSelectedContentDescriptor();
      return selectedContentDescriptor != null ? Lists.newArrayList(selectedContentDescriptor) : Lists.newArrayList();
    }

    Collection<RunContentDescriptor> descriptors =
      ExecutionHelper.findRunningConsole(project, dom -> dom.getExecutionConsole() instanceof PyCodeExecutor && isAlive(dom));

    if (descriptors.isEmpty() && toolWindow != null && toolWindow.isInitialized()) {
      return toolWindow.getConsoleContentDescriptors();
    }
    else {
      return descriptors;
    }
  }

  private static boolean isAlive(RunContentDescriptor dom) {
    ProcessHandler processHandler = dom.getProcessHandler();
    return processHandler != null && !processHandler.isProcessTerminated();
  }

  public static void findCodeExecutor(@NotNull DataContext dataContext,
                                      @NotNull Consumer<PyCodeExecutor> consumer,
                                      @Nullable Editor editor,
                                      @Nullable Project project,
                                      boolean requestFocusToConsole) {
    if (project != null) {
      if (canFindConsole(project, null)) {
        selectConsole(dataContext, project, consumer, editor, requestFocusToConsole);
      }
      else {
        showConsole(project, consumer);
      }
    }
  }

  private static void showConsole(final Project project, final Consumer<PyCodeExecutor> consumer) {
    final PythonConsoleToolWindow toolWindow = PythonConsoleToolWindow.getInstance(project);

    if (toolWindow != null && toolWindow.getConsoleContentDescriptors().size() > 0) {
      toolWindow.activate(() -> {
        List<RunContentDescriptor> descs = toolWindow.getConsoleContentDescriptors();

        RunContentDescriptor descriptor = descs.get(0);
        if (descriptor != null && descriptor.getExecutionConsole() instanceof PyCodeExecutor) {
          consumer.consume((PyCodeExecutor)descriptor.getExecutionConsole());
        }
      });
    }
    else {
      startNewConsoleInstance(project, consumer, null, null);
    }
  }

  public static void startNewConsoleInstance(@NotNull final Project project,
                                             @NotNull final Consumer<PyCodeExecutor> consumer,
                                             @Nullable String runFileText,
                                             @Nullable PythonRunConfiguration config) {
    PythonConsoleRunnerFactory consoleRunnerFactory = PythonConsoleRunnerFactory.getInstance();
    PydevConsoleRunner runner;
    if (runFileText == null || config == null) {
      runner = consoleRunnerFactory.createConsoleRunner(project, null);
    }
    else {
      runner = consoleRunnerFactory.createConsoleRunnerWithFile(project, null, runFileText, config);
    }
    final PythonConsoleToolWindow toolWindow = PythonConsoleToolWindow.getInstance(project);
    runner.addConsoleListener(new PydevConsoleRunner.ConsoleListener() {
      @Override
      public void handleConsoleInitialized(@NotNull LanguageConsoleView consoleView) {
        if (consoleView instanceof PyCodeExecutor) {
          consumer.consume((PyCodeExecutor)consoleView);
          if (toolWindow != null) {
            toolWindow.getToolWindow().show(null);
          }
        }
      }
    });
    runner.run(false);
  }

  public static boolean canFindConsole(@Nullable Project project, @Nullable String sdkHome) {
    if (project != null) {
      Collection<RunContentDescriptor> descriptors = getConsoles(project);
      if (sdkHome == null) {
        return descriptors.size() > 0;
      }
      else {
        for (RunContentDescriptor descriptor : descriptors) {
          final ExecutionConsole console = descriptor.getExecutionConsole();
          if (console instanceof PythonConsoleView) {
            final PythonConsoleView pythonConsole = (PythonConsoleView)console;
            if (pythonConsole.getText().startsWith(sdkHome)) {
              return true;
            }
          }
        }
        return false;
      }
    }
    else {
      return false;
    }
  }

  public static void executeInConsole(@NotNull PyCodeExecutor codeExecutor, @Nullable String text, @Nullable Editor editor) {
    codeExecutor.executeCode(text, editor);
  }
}
