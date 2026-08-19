package io.maru.lastnotif;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30) // Pick an arbitrary modern SDK version
public class LastNotifPollerBootReceiverTest {

    private Context context;
    private Intent intent;
    private LastNotifPollerBootReceiver receiver;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Instead of mocking Intent which fails on robolectric with mockito-inline, just instantiate it
        intent = new Intent();
        receiver = new LastNotifPollerBootReceiver();
    }

    @Test
    public void testOnReceive_nullIntent_doesNothing() {
        try (MockedConstruction<LastNotifStorage> mockedStorage = Mockito.mockConstruction(LastNotifStorage.class);
             MockedStatic<LastNotifPollerService> mockedService = Mockito.mockStatic(LastNotifPollerService.class);
             MockedStatic<LastNotifPollerAlarmScheduler> mockedScheduler = Mockito.mockStatic(LastNotifPollerAlarmScheduler.class)) {

            receiver.onReceive(context, null);

            mockedService.verify(() -> LastNotifPollerService.start(any(Context.class)), never());
            mockedScheduler.verify(() -> LastNotifPollerAlarmScheduler.schedule(any(Context.class)), never());
        }
    }

    @Test
    public void testOnReceive_wrongAction_doesNothing() {
        intent.setAction("some.other.ACTION");

        try (MockedConstruction<LastNotifStorage> mockedStorage = Mockito.mockConstruction(LastNotifStorage.class);
             MockedStatic<LastNotifPollerService> mockedService = Mockito.mockStatic(LastNotifPollerService.class);
             MockedStatic<LastNotifPollerAlarmScheduler> mockedScheduler = Mockito.mockStatic(LastNotifPollerAlarmScheduler.class)) {

            receiver.onReceive(context, intent);

            mockedService.verify(() -> LastNotifPollerService.start(any(Context.class)), never());
            mockedScheduler.verify(() -> LastNotifPollerAlarmScheduler.schedule(any(Context.class)), never());
        }
    }

    @Test
    public void testOnReceive_bootCompleted_serviceNotRunning_doesNothing() {
        intent.setAction(Intent.ACTION_BOOT_COMPLETED);

        try (MockedConstruction<LastNotifStorage> mockedStorage = Mockito.mockConstruction(LastNotifStorage.class, (mock, ctx) -> {
            when(mock.isServiceRunning()).thenReturn(false);
        });
             MockedStatic<LastNotifPollerService> mockedService = Mockito.mockStatic(LastNotifPollerService.class);
             MockedStatic<LastNotifPollerAlarmScheduler> mockedScheduler = Mockito.mockStatic(LastNotifPollerAlarmScheduler.class)) {

            receiver.onReceive(context, intent);

            mockedService.verify(() -> LastNotifPollerService.start(any(Context.class)), never());
            mockedScheduler.verify(() -> LastNotifPollerAlarmScheduler.schedule(any(Context.class)), never());
        }
    }

    @Test
    public void testOnReceive_bootCompleted_serviceRunning_startsServiceAndSchedules() {
        intent.setAction(Intent.ACTION_BOOT_COMPLETED);

        try (MockedConstruction<LastNotifStorage> mockedStorage = Mockito.mockConstruction(LastNotifStorage.class, (mock, ctx) -> {
            when(mock.isServiceRunning()).thenReturn(true);
        });
             MockedStatic<LastNotifPollerService> mockedService = Mockito.mockStatic(LastNotifPollerService.class);
             MockedStatic<LastNotifPollerAlarmScheduler> mockedScheduler = Mockito.mockStatic(LastNotifPollerAlarmScheduler.class)) {

            receiver.onReceive(context, intent);

            mockedService.verify(() -> LastNotifPollerService.start(context), times(1));
            mockedScheduler.verify(() -> LastNotifPollerAlarmScheduler.schedule(context), times(1));
        }
    }

    @Test
    public void testOnReceive_packageReplaced_serviceRunning_startsServiceAndSchedules() {
        intent.setAction(Intent.ACTION_MY_PACKAGE_REPLACED);

        try (MockedConstruction<LastNotifStorage> mockedStorage = Mockito.mockConstruction(LastNotifStorage.class, (mock, ctx) -> {
            when(mock.isServiceRunning()).thenReturn(true);
        });
             MockedStatic<LastNotifPollerService> mockedService = Mockito.mockStatic(LastNotifPollerService.class);
             MockedStatic<LastNotifPollerAlarmScheduler> mockedScheduler = Mockito.mockStatic(LastNotifPollerAlarmScheduler.class)) {

            receiver.onReceive(context, intent);

            mockedService.verify(() -> LastNotifPollerService.start(context), times(1));
            mockedScheduler.verify(() -> LastNotifPollerAlarmScheduler.schedule(context), times(1));
        }
    }
}
