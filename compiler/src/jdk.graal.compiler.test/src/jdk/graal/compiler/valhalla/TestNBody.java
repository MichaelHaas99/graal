package jdk.graal.compiler.valhalla;

import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;

public class TestNBody extends JTTTest {
    static value class Particle {
        private final double x, y, z, vx, vy, vz;

        public Particle(double x, double y, double z, double vx, double vy, double vz) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
        }

        public Particle update() {
            return new Particle(x + vx, y + vy, z + vz, vx, vy, vz);
        }
    }

    static final int N = 1_000_000;
    static Particle[] particles = new Particle[N];

    static {
        initialize();
    }

    static void initialize() {
        for (int i = 0; i < N; i++)
            particles[i] = new Particle(Math.random(), Math.random(), Math.random(), 0.01, 0.01, 0.01);
    }

    // @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void simulate() {
        for (int i = 0; i < N; i++)
            particles[i] = particles[i].update();
    }

    @Test
    public void run2() {
        getCode(getResolvedJavaMethod("simulate"), null, true, true, getInitialOptions());
    }

}
