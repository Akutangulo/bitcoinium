
package com.veken0m.bitcoinium;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.widget.RemoteViews;

import com.veken0m.bitcoinium.exchanges.Exchange;
import com.veken0m.bitcoinium.utils.CurrencyUtils;
import com.veken0m.bitcoinium.utils.Utils;
import com.xeiam.xchange.ExchangeFactory;
import com.xeiam.xchange.currency.Currencies;
import com.xeiam.xchange.currency.CurrencyPair;
import com.xeiam.xchange.dto.marketdata.Ticker;

public class WidgetProvider extends BaseWidgetProvider {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (REFRESH.equals(intent.getAction())) {
            setPriceWidgetAlarm(context);
        } else {
            super.onReceive(context, intent);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager,
            int[] appWidgetIds) {
        setPriceWidgetAlarm(context);
    }

    /**
     * This class lets us refresh the widget whenever we want to
     */
    public static class UpdateService extends IntentService {

        public void buildUpdate(Context context) {
            AppWidgetManager widgetManager = AppWidgetManager
                    .getInstance(context);
            ComponentName widgetComponent = new ComponentName(context,
                    WidgetProvider.class);
            int[] widgetIds = widgetManager.getAppWidgetIds(widgetComponent);
            PendingIntent pendingIntent;

            readGeneralPreferences(context);

            if (!pref_wifionly || checkWiFiConnected(context)) {

                for (int appWidgetId : widgetIds) {

                    // Load Widget preferences
                    String pref_exchange = WidgetConfigureActivity
                            .loadExchangePref(context, appWidgetId);
                    pref_currency = WidgetConfigureActivity
                            .loadCurrencyPref(context, appWidgetId);

                    Exchange exchange = getExchange(pref_exchange);
                    String exchangeName = exchange.getExchangeName();
                    String pref_widgetExchange = exchange.getClassName();
                    String defaultCurrency = exchange.getMainCurrency();
                    String exchangeKey = exchange.getIdentifier();
                    
                    Boolean tickerBidAsk = exchange.supportsTickerBidAsk();
                    
                    if (pref_tapToUpdate) {
                        Intent intent = new Intent(this, WidgetProvider.class);
                        intent.setAction(REFRESH);
                        pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent, 0);
                    } else {
                        Intent intent = new Intent(context, MainActivity.class);
                        intent.putExtra("exchangeKey", exchangeKey);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        pendingIntent = PendingIntent.getActivity(
                                context, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                    }
                    
                    RemoteViews views = new RemoteViews(
                            context.getPackageName(), R.layout.appwidget);
                    
                    views.setOnClickPendingIntent(R.id.widgetButton,
                            pendingIntent);

                    readAllWidgetPreferences(context, exchangeKey, defaultCurrency);

                    if (pref_currency.length() == 3 || pref_currency.length() == 7) {

                        try {

                            //TODO: Move this to Utils and generalize 
                            CurrencyPair pair = CurrencyUtils.stringToCurrencyPair(pref_currency);
                            if (!pair.baseCurrency.equals(Currencies.BTC)) {
                                    exchangeName = exchangeName + " (" + pair.baseCurrency + ")";
                            }
                            
                            // Get a unique id for the exchange/currency pair combo
                            int NOTIFY_ID = (exchangeName + pair.baseCurrency + pair.counterCurrency)
                                    .hashCode();
                            String pairId = exchange.getIdentifier() + pair.baseCurrency + pair.counterCurrency;

                            // Get ticker using XChange
                            final Ticker ticker = ExchangeFactory.INSTANCE
                                    .createExchange(pref_widgetExchange)
                                    .getPollingMarketDataService()
                                    .getTicker(pair.baseCurrency, pair.counterCurrency);

                            // Retrieve values from ticker
                            final float lastFloat = ticker.getLast()
                                    .getAmount().floatValue();
                            final String lastString = Utils.formatWidgetMoney(
                                    lastFloat, pair.counterCurrency, true);

                            String volumeString = "N/A";
                            if (!(ticker.getVolume() == null)) {
                                float volumeFloat = ticker.getVolume()
                                        .floatValue();

                                volumeString = Utils.formatDecimal(volumeFloat,
                                        2, false);
                            }

                            if (((ticker.getHigh() == null) || pref_widgetbidask)
                                    && tickerBidAsk) {
                                setBidAsk(ticker, views, pair.counterCurrency);
                            } else {
                                setHighLow(ticker, views, pair.counterCurrency);
                            }

                            views.setTextViewText(R.id.widgetExchange,
                                    exchangeName);
                            views.setTextViewText(R.id.widgetLastText,
                                    lastString);
                            views.setTextViewText(R.id.widgetVolText,
                                    "Volume: " + volumeString);

                            if (pref_displayUpdates) {
                                String text = exchangeName + " Updated!";
                                createTicker(context, R.drawable.bitcoin, text);
                            }

                            SharedPreferences prefs = PreferenceManager
                                    .getDefaultSharedPreferences(context);

                            if (pref_priceAlarm) {

                                // If previous alarm key exists, purge them and
                                // notify the user
                                if (prefs.contains(exchangeKey + "Upper")
                                        || prefs.contains(exchangeKey + "Lower")) {
                                    prefs.edit().remove(exchangeKey + "Upper").commit();
                                    prefs.edit().remove(exchangeKey + "Lower").commit();
                                    prefs.edit().remove(exchangeKey + "TickerPref").commit();

                                    notifyUserOfAlarmUpgrade(context);
                                }

                                checkAlarm(context, pair.counterCurrency, pair.baseCurrency, pairId,
                                        lastFloat,
                                        exchange, NOTIFY_ID);
                            }

                            Boolean pref_notifTicker = prefs.getBoolean(pairId + "TickerPref",
                                    false);

                            if (pref_enableTicker && pref_notifTicker) {

                                String msg = pair.baseCurrency + " value: " + lastString
                                        + " on " + exchangeName;
                                String title = exchangeKey + pair.baseCurrency + " @ " + lastString;

                                createPermanentNotification(context,
                                        R.drawable.bitcoin, title, msg,
                                        NOTIFY_ID);
                            } else {
                                removePermanentNotification(context, NOTIFY_ID);
                            }

                            String refreshedTime = "Updated @ "
                                    + Utils.getCurrentTime(context);
                            views.setTextViewText(R.id.label, refreshedTime);

                            updateWidgetTheme(views);

                        } catch (Exception e) {
                            e.printStackTrace();
                            if (pref_enableWidgetCustomization) {
                                views.setTextColor(R.id.label,
                                        pref_widgetRefreshFailedColor);
                            } else {
                                views.setTextColor(R.id.label, Color.RED);
                            }

                            if (pref_displayUpdates) {
                                String txt = exchangeName + " Update failed!";
                                createTicker(context, R.drawable.bitcoin, txt);
                            }
                        } finally {
                            widgetManager.updateAppWidget(appWidgetId, views);
                        }
                    }
                }
            }
        }

        public Exchange getExchange(String pref_widget) {
            try {
                return new Exchange(getBaseContext(), pref_widget);
            } catch (Exception e) {
                return new Exchange(getBaseContext(), "MtGoxExchange");
            }
        }

        public void setTextColors(RemoteViews views, int color) {
            views.setTextColor(R.id.widgetLowText, color);
            views.setTextColor(R.id.widgetHighText, color);
            views.setTextColor(R.id.widgetVolText, color);
        }

        public void setBidAsk(Ticker ticker, RemoteViews views,
                String pref_currency) {

            float bidFloat = ticker.getBid().getAmount().floatValue();
            float askFloat = ticker.getAsk().getAmount().floatValue();

            String bidString = Utils.formatWidgetMoney(bidFloat, pref_currency,
                    false);
            String askString = Utils.formatWidgetMoney(askFloat, pref_currency,
                    false);

            if (pref_enableWidgetCustomization) {
                setTextColors(views, pref_secondaryWidgetTextColor);
            } else {
                setTextColors(views, Color.WHITE);
            }

            views.setTextViewText(R.id.widgetLowText, bidString);
            views.setTextViewText(R.id.widgetHighText, askString);
        }

        public void setHighLow(Ticker ticker, RemoteViews views,
                String pref_currency) {

            float highFloat = ticker.getHigh().getAmount().floatValue();
            float lowFloat = ticker.getLow().getAmount().floatValue();

            String highString = Utils.formatWidgetMoney(highFloat,
                    pref_currency, false);
            String lowString = Utils.formatWidgetMoney(lowFloat, pref_currency,
                    false);
            if (pref_enableWidgetCustomization) {
                setTextColors(views, pref_secondaryWidgetTextColor);
            } else {
                setTextColors(views, Color.LTGRAY);
            }
            views.setTextViewText(R.id.widgetLowText, lowString);
            views.setTextViewText(R.id.widgetHighText, highString);
        }

        public void updateWidgetTheme(RemoteViews views) {
            // set the color
            if (pref_enableWidgetCustomization) {
                views.setInt(R.id.widget_layout,
                        "setBackgroundColor",
                        pref_backgroundWidgetColor);
                views.setTextColor(R.id.widgetLastText,
                        pref_mainWidgetTextColor);
                views.setTextColor(R.id.widgetExchange,
                        pref_mainWidgetTextColor);
                views.setTextColor(R.id.label,
                        pref_widgetRefreshSuccessColor);
                views.setTextColor(R.id.widgetVolText, pref_secondaryWidgetTextColor);

            } else {
                views.setInt(
                        R.id.widget_layout,
                        "setBackgroundColor",
                        getResources().getColor(
                                R.color.widgetBackgroundColor));
                views.setTextColor(
                        R.id.widgetLastText,
                        getResources().getColor(
                                R.color.widgetMainTextColor));
                views.setTextColor(
                        R.id.widgetExchange,
                        getResources().getColor(
                                R.color.widgetMainTextColor));
                views.setTextColor(R.id.label, Color.GREEN);
            }
        }

        public void checkAlarm(Context context, String counterCurrency, String baseCurrency,
                String pairId,
                float lastFloat, Exchange exchange, int NOTIFY_ID) {

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

            String pref_notifLimitUpper = prefs.getString(pairId + "Upper", "999999");
            String pref_notifLimitLower = prefs.getString(pairId + "Lower", "0");

            Boolean triggered = false;
            try {
                triggered = !Utils.isBetween(lastFloat,
                        Float.valueOf(pref_notifLimitLower),
                        Float.valueOf(pref_notifLimitUpper));
            } catch (Exception e) {
                e.printStackTrace();
                triggered = false;
                // TODO: Fix toast message for invalid thresholds
                // String text = exchangeName +
                // "notification alarm thresholds are invalid";
                // Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            }

            if (triggered) {
                String lastString = Utils.formatWidgetMoney(lastFloat,
                        counterCurrency, true);
                createNotification(context, lastString, exchange.getExchangeName(), NOTIFY_ID,
                        pref_currency);

                if (pref_alarmClock)
                    setAlarmClock(context);
            }
        }

        public void notifyUserOfAlarmUpgrade(Context ctxt) {
            int icon = R.drawable.bitcoin;
            NotificationManager mNotificationManager = (NotificationManager) ctxt
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            long when = System.currentTimeMillis();
            String tickerText = "Bitcoinium Price Alarm upgraded. \nPlease reset your alarms! Sorry for the inconvenience";
            String notificationText = "Please reset your alarms!\nSorry for the inconvenience";
            Notification notification = new Notification(icon, tickerText, when);

            Intent notificationIntent = new Intent(ctxt, PriceAlarmPreferencesActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(ctxt, 0,
                    notificationIntent, 0);

            notification.setLatestEventInfo(ctxt, "Price Alarm upgraded", notificationText,
                    contentIntent);

            notification.defaults |= Notification.DEFAULT_VIBRATE;

            mNotificationManager.notify(1337, notification);
        }

        public UpdateService() {
            super("WidgetProvider$UpdateService");
        }

        @Override
        public void onCreate() {
            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            super.onStartCommand(intent, flags, startId);
            return START_STICKY;
        }

        @Override
        public void onHandleIntent(Intent intent) {
            buildUpdate(this);
        }
    }
    
    public void onDestoy(Context context) {
        final AlarmManager m = (AlarmManager) context
                .getSystemService(Context.ALARM_SERVICE);
        
        m.cancel(widgetPriceWidgetRefreshService);
    }

}
