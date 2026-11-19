/* @Author Ronan
 * @Author Vcoc
        Name: Steward
        Map(s): Foyer
        Info: Commands
        Script: commands.js
*/

var status;

var levels = ["Common", "Donator", "JrGM", "GM", "SuperGM", "Developer", "Admin"];
var executor;
var maxTier;

function start() {
    status = -1;
    const CommandsExecutor = Java.type('client.command.CommandsExecutor');
    executor = CommandsExecutor.getInstance();
    maxTier = Math.min(cm.getPlayer().gmLevel(), levels.length - 1);
    if (maxTier <= 0) {
        // Only one tier: show its list straight away.
        cm.sendOk("These are the commands available to you:\r\n\r\n" + executor.commandListText(0));
        return;
    }
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1 || maxTier <= 0) {
        cm.dispose();
    } else {
        if (mode == 0 && type > 0) {
            cm.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            var sendStr = "There are all available commands for you:\r\n\r\n#b";
            for (var i = 0; i <= maxTier; i++) {
                sendStr += "#L" + i + "#" + levels[i] + "#l\r\n";
            }

            cm.sendSimple(sendStr);
        } else if (status == 1) {
            if (selection > maxTier) {
                selection = maxTier;
            } else if (selection < 0) {
                selection = 0;
            }

            var sendStr = "The following commands are available for #b" + levels[selection] + "#k:\r\n\r\n";
            sendStr += executor.commandListText(selection);

            cm.sendPrev(sendStr);
        } else {
            cm.dispose();
        }
    }
}
