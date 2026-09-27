package pzopt;

import org.lwjgl.opengl.GL20;

/** GLSL program compilation for the pzopt render-thread passes (sources are Java strings; failures are logged, 0 returned). */
final class Shaders {
   private Shaders() {
   }

   static int program(String name, String vertexSource, String fragmentSource) {
      int vs = compile(name, GL20.GL_VERTEX_SHADER, vertexSource);
      if (vs == 0) {
         return 0;
      }
      int fs = compile(name, GL20.GL_FRAGMENT_SHADER, fragmentSource);
      if (fs == 0) {
         GL20.glDeleteShader(vs);
         return 0;
      }
      int program = GL20.glCreateProgram();
      GL20.glAttachShader(program, vs);
      GL20.glAttachShader(program, fs);
      GL20.glLinkProgram(program);
      GL20.glDeleteShader(vs);
      GL20.glDeleteShader(fs);
      if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
         Log.warn(name + ": link failed: " + GL20.glGetProgramInfoLog(program, 4096));
         GL20.glDeleteProgram(program);
         return 0;
      }
      return program;
   }

   private static int compile(String name, int type, String source) {
      int shader = GL20.glCreateShader(type);
      GL20.glShaderSource(shader, source);
      GL20.glCompileShader(shader);
      if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn(name + ": shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
         GL20.glDeleteShader(shader);
         return 0;
      }
      return shader;
   }

   /**
    * A game program we patched, bound: its own sampler2Ds back on the units stock gives them. After the link the game's
    * ShaderProgram walks the active uniforms in the driver's order and hands every sampler2D the next unit (the first one
    * keeps its link-time value). Which uniform a driver lists first is its own choice: where one of ours (pz*, ppl*) comes
    * before the game's DIFFUSE, DIFFUSE lands on unit 1 or 2 while the game binds the world to unit 0, and the whole
    * picture draws black (the likely cause of the black world on AMD / Windows with the god rays' screen.frag patch,
    * 2026-09-27, not reproduced here: NVIDIA and Mesa list DIFFUSE first, and the line below logs any move). Here the
    * game's samplers are numbered 0, 1, 2... in the driver's order without ours, as stock would have; the caller sets its
    * own samplers' units.
    */
   static void stockSamplerUnits(int program, String what) {
      int n = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
      int unit = 0;
      StringBuilder moved = null;
      try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
         java.nio.IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
         for (int i = 0; i < n; i++) {
            String name = GL20.glGetActiveUniform(program, i, 255, size, type);
            if (type.get(0) != GL20.GL_SAMPLER_2D || name.startsWith("pz") || name.startsWith("ppl")) {
               continue;
            }
            int loc = GL20.glGetUniformLocation(program, name);
            if (loc < 0) {
               continue;
            }
            int was = GL20.glGetUniformi(program, loc);
            if (was != unit) {
               GL20.glUniform1i(loc, unit);
               moved = (moved == null ? new StringBuilder() : moved.append(", ")).append(name).append(' ').append(was).append(" -> ").append(unit);
            }
            unit++;
         }
      }
      if (moved != null) {
         Log.info(what + ": the game's samplers back on their stock units in program " + program + " (" + moved + ")");
      }
   }
}
